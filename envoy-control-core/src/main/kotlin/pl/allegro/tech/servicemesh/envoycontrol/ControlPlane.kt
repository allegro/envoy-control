package pl.allegro.tech.servicemesh.envoycontrol

import io.envoyproxy.controlplane.cache.NodeGroup
import io.envoyproxy.controlplane.cache.SnapshotCache
import io.envoyproxy.controlplane.cache.v3.Snapshot
import io.envoyproxy.controlplane.server.DefaultExecutorGroup
import io.envoyproxy.controlplane.server.ExecutorGroup
import io.envoyproxy.controlplane.server.V2DiscoveryServer
import io.envoyproxy.controlplane.server.V3DiscoveryServer
import io.envoyproxy.controlplane.server.callback.SnapshotCollectingCallback
import io.grpc.Server
import io.grpc.netty.NettyServerBuilder
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics
import io.netty.channel.nio.NioEventLoopGroup
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group
import pl.allegro.tech.servicemesh.envoycontrol.groups.GroupChangeWatcher
import pl.allegro.tech.servicemesh.envoycontrol.groups.MetadataNodeGroup
import pl.allegro.tech.servicemesh.envoycontrol.groups.NodeMetadataValidator
import pl.allegro.tech.servicemesh.envoycontrol.server.CachedProtoResourcesSerializer
import pl.allegro.tech.servicemesh.envoycontrol.server.ExecutorProperties
import pl.allegro.tech.servicemesh.envoycontrol.server.ExecutorType
import pl.allegro.tech.servicemesh.envoycontrol.server.ServerProperties
import pl.allegro.tech.servicemesh.envoycontrol.server.callbacks.CompositeDiscoveryServerCallbacks
import pl.allegro.tech.servicemesh.envoycontrol.server.callbacks.LoggingDiscoveryServerCallbacks
import pl.allegro.tech.servicemesh.envoycontrol.server.callbacks.MeteredConnectionsCallbacks
import pl.allegro.tech.servicemesh.envoycontrol.services.MultiClusterState
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.clusters.EnvoyClustersFactory
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.routes.EnvoyEgressRoutesFactory
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.routes.EnvoyIngressRoutesFactory
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.EnvoySnapshotFactory
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotUpdater
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotsVersions
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.endpoints.EnvoyEndpointsFactory
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters.AccessLogFilterFactory
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters.EnvoyHttpFilters
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.EnvoyListenersFactory
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.routes.ServiceTagMetadataGenerator
import pl.allegro.tech.servicemesh.envoycontrol.utils.DirectScheduler
import pl.allegro.tech.servicemesh.envoycontrol.utils.ParallelScheduler
import pl.allegro.tech.servicemesh.envoycontrol.utils.ParallelizableScheduler
import pl.allegro.tech.servicemesh.envoycontrol.v3.SimpleCache
import reactor.core.Disposable
import reactor.core.publisher.Flux
import reactor.core.scheduler.Schedulers
import java.time.Clock
import java.util.concurrent.BlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ControlPlane private constructor(
    val grpcServer: Server,
    val snapshotUpdater: SnapshotUpdater,
    val nodeGroup: NodeGroup<Group>,
    val cache: SnapshotCache<Group, Snapshot>,
    private val changes: Flux<MultiClusterState>
) : AutoCloseable {

    private var servicesDisposable: Disposable? = null

    companion object {
        fun builder(
            properties: EnvoyControlProperties,
            meterRegistry: MeterRegistry
        ) = ControlPlaneBuilder(properties, meterRegistry)
    }

    fun start() {
        servicesDisposable = snapshotUpdater
            .start(changes)
            .subscribe()
        grpcServer.start()
    }

    override fun close() {
        servicesDisposable?.dispose()
        grpcServer.shutdownNow()
        grpcServer.awaitTermination()
    }

    class ControlPlaneBuilder(
        val properties: EnvoyControlProperties,
        val meterRegistry: MeterRegistry
    ) {
        var grpcServerExecutor: Executor? = null
        var nioEventLoopExecutor: Executor? = null
        var executorGroup: ExecutorGroup? = null
        var globalSnapshotExecutor: Executor? = null
        var groupSnapshotParallelExecutorSupplier: () -> Executor? = { null }
        var metrics: EnvoyControlMetrics = DefaultEnvoyControlMetrics(meterRegistry = meterRegistry)
        var envoyHttpFilters: EnvoyHttpFilters = EnvoyHttpFilters.emptyFilters

        val accessLogFilterFactory = AccessLogFilterFactory()

        var nodeGroup: NodeGroup<Group> = MetadataNodeGroup(
            properties = properties.envoy.snapshot,
            accessLogFilterFactory = accessLogFilterFactory
        )

        fun build(changes: Flux<MultiClusterState>): ControlPlane {
            if (grpcServerExecutor == null) {
                grpcServerExecutor = buildThreadPoolExecutor()
            }

            if (nioEventLoopExecutor == null) {
                // unbounded executor - netty will only use configured number of threads
                // (by nioEventLoopThreadCount property or default netty value: <number of CPUs> * 2)
                nioEventLoopExecutor = newMeteredCachedThreadPool("grpc-worker-event-loop")
            }

            if (executorGroup == null) {
                executorGroup = buildExecutorGroup()
            }

            if (globalSnapshotExecutor == null) {
                globalSnapshotExecutor = newMeteredFixedThreadPool(
                    "snapshot-update",
                    properties.server.globalSnapshotUpdatePoolSize
                )
            }

            val groupSnapshotProperties = properties.server.groupSnapshotUpdateScheduler

            val groupSnapshotScheduler = buildGroupSnapshotScheduler(groupSnapshotProperties)
            val cache = SimpleCache(nodeGroup, properties.envoy.snapshot.shouldSendMissingEndpoints)
            val groupChangeWatcher = GroupChangeWatcher(cache, metrics, meterRegistry)
            val meteredConnectionsCallbacks = MeteredConnectionsCallbacks().also {
                meterRegistry.gauge("grpc.all-connections", it.connections)
                MeteredConnectionsCallbacks.MetricsStreamType.values().map { type ->
                    meterRegistry.gauge("grpc.connections.${type.name.toLowerCase()}", it.connections(type))
                }
            }
            val loggingDiscoveryServerCallbacks = LoggingDiscoveryServerCallbacks(
                properties.server.logFullRequest,
                properties.server.logFullResponse
            )
            val cachedProtoResourcesSerializer = CachedProtoResourcesSerializer(
                meterRegistry,
                properties.server.reportProtobufCacheMetrics
            )

            val snapshotsVersions = SnapshotsVersions()
            val snapshotProperties = properties.envoy.snapshot
            val envoySnapshotFactory = EnvoySnapshotFactory(
                ingressRoutesFactory = EnvoyIngressRoutesFactory(snapshotProperties, envoyHttpFilters),
                egressRoutesFactory = EnvoyEgressRoutesFactory(snapshotProperties),
                clustersFactory = EnvoyClustersFactory(snapshotProperties),
                endpointsFactory = EnvoyEndpointsFactory(
                    snapshotProperties, ServiceTagMetadataGenerator(snapshotProperties.routing.serviceTags)
                ),
                listenersFactory = EnvoyListenersFactory(
                    snapshotProperties,
                    envoyHttpFilters
                ),
                // Remember when LDS change we have to send RDS again
                snapshotsVersions = snapshotsVersions,
                properties = snapshotProperties,
                meterRegistry = meterRegistry
            )

            return ControlPlane(
                grpcServer(
                    properties.server,
                    createV2Server(
                        cache,
                        loggingDiscoveryServerCallbacks,
                        meteredConnectionsCallbacks,
                        groupChangeWatcher,
                        cachedProtoResourcesSerializer
                    ),
                    createV3Server(
                        cache,
                        loggingDiscoveryServerCallbacks,
                        meteredConnectionsCallbacks,
                        groupChangeWatcher,
                        cachedProtoResourcesSerializer
                    ),
                    nioEventLoopExecutor!!,
                    grpcServerExecutor!!
                ),
                SnapshotUpdater(
                    cache,
                    properties.envoy.snapshot,
                    envoySnapshotFactory,
                    Schedulers.fromExecutor(globalSnapshotExecutor!!),
                    groupSnapshotScheduler,
                    groupChangeWatcher.onGroupAdded(),
                    meterRegistry,
                    snapshotsVersions,
                    envoyHttpFilters
                ),
                nodeGroup,
                cache,
                changes
            )
        }

        private fun createV3Server(
            cache: SimpleCache<Group>,
            loggingDiscoveryServerCallbacks: LoggingDiscoveryServerCallbacks,
            meteredConnectionsCallbacks: MeteredConnectionsCallbacks,
            groupChangeWatcher: GroupChangeWatcher,
            cachedProtoResourcesSerializer: CachedProtoResourcesSerializer
        ): V3DiscoveryServer {
            val compositeDiscoveryCallbacksV3 = listOf(
                CompositeDiscoveryServerCallbacks(
                    meterRegistry,
                    buildSnapshotCollectingCallback(cache),
                    loggingDiscoveryServerCallbacks,
                    meteredConnectionsCallbacks,
                    NodeMetadataValidator(properties.envoy.snapshot)
                )
            )

            return V3DiscoveryServer(
                compositeDiscoveryCallbacksV3,
                groupChangeWatcher,
                executorGroup,
                cachedProtoResourcesSerializer
            )
        }

        private fun createV2Server(
            cache: SimpleCache<Group>,
            loggingDiscoveryServerCallbacks: LoggingDiscoveryServerCallbacks,
            meteredConnectionsCallbacks: MeteredConnectionsCallbacks,
            groupChangeWatcher: GroupChangeWatcher,
            cachedProtoResourcesSerializer: CachedProtoResourcesSerializer
        ): V2DiscoveryServer {
            val compositeDiscoveryCallbacksV2 = listOf(
                CompositeDiscoveryServerCallbacks(
                    meterRegistry,
                    buildSnapshotCollectingCallback(cache),
                    loggingDiscoveryServerCallbacks,
                    meteredConnectionsCallbacks,
                    NodeMetadataValidator(properties.envoy.snapshot)
                )
            )
            return V2DiscoveryServer(
                compositeDiscoveryCallbacksV2,
                groupChangeWatcher,
                executorGroup,
                cachedProtoResourcesSerializer
            )
        }

        private fun buildSnapshotCollectingCallback(
            cache: SimpleCache<Group>
        ): SnapshotCollectingCallback<Group, Snapshot> {
            val cleanupProperties = properties.server.snapshotCleanup
            return SnapshotCollectingCallback(
                cache,
                nodeGroup,
                Clock.systemDefaultZone(),
                emptySet(),
                cleanupProperties.collectAfterMillis.toMillis(),
                cleanupProperties.collectionIntervalMillis.toMillis()
            )
        }

        private fun buildGroupSnapshotScheduler(groupSnapshotProperties: ExecutorProperties): ParallelizableScheduler {
            return when (groupSnapshotProperties.type) {
                ExecutorType.DIRECT -> DirectScheduler
                ExecutorType.PARALLEL -> ParallelScheduler(
                    scheduler = Schedulers.fromExecutor(
                        groupSnapshotParallelExecutorSupplier()
                            ?: newMeteredFixedThreadPool(
                                "group-snapshot",
                                groupSnapshotProperties.parallelPoolSize
                            )
                    ),
                    parallelism = groupSnapshotProperties.parallelPoolSize
                )
            }
        }

        private fun buildExecutorGroup(): ExecutorGroup? {
            return when (properties.server.executorGroup.type) {
                ExecutorType.DIRECT -> DefaultExecutorGroup()
                ExecutorType.PARALLEL -> {
                    // TODO(https://github.com/allegro/envoy-control/issues/103) this implementation of parallel
                    //   executor group is invalid, because it may lead to sending XDS responses out of order for
                    //   given DiscoveryRequestStreamObserver. We should switch to multiple, single-threaded
                    //   ThreadPoolExecutors. More info in linked task.
                    val executor = newMeteredFixedThreadPool(
                        "discovery-responses-executor",
                        properties.server.executorGroup.parallelPoolSize
                    )
                    ExecutorGroup { executor }
                }
            }
        }

        private fun buildThreadPoolExecutor(): ThreadPoolExecutor {
            return newMeteredThreadPoolExecutor(
                properties.server.serverPoolSize,
                properties.server.serverPoolSize,
                properties.server.serverPoolKeepAlive.toMillis(),
                LinkedBlockingQueue<Runnable>(),
                "grpc-server-worker"
            )
        }

        fun withNodeGroup(nodeGroup: NodeGroup<Group>): ControlPlaneBuilder {
            this.nodeGroup = nodeGroup
            return this
        }

        fun withGrpcServerExecutor(executor: Executor): ControlPlaneBuilder {
            grpcServerExecutor = executor
            return this
        }

        fun withNioEventLoopExecutor(executor: Executor): ControlPlaneBuilder {
            nioEventLoopExecutor = executor
            return this
        }

        fun withExecutorGroup(executor: ExecutorGroup): ControlPlaneBuilder {
            executorGroup = executor
            return this
        }

        fun withGlobalSnapshotExecutor(executor: Executor): ControlPlaneBuilder {
            globalSnapshotExecutor = executor
            return this
        }

        fun withGroupSnapshotParallelExecutor(executorSupplier: () -> Executor): ControlPlaneBuilder {
            groupSnapshotParallelExecutorSupplier = executorSupplier
            return this
        }

        fun withMetrics(metrics: EnvoyControlMetrics): ControlPlaneBuilder {
            this.metrics = metrics
            return this
        }

        fun withEnvoyHttpFilters(envoyHttpFilters: EnvoyHttpFilters): ControlPlaneBuilder {
            this.envoyHttpFilters = envoyHttpFilters
            return this
        }

        private fun NettyServerBuilder.withV2EnvoyServices(discoveryServer: V2DiscoveryServer): NettyServerBuilder {
            return this.addService(discoveryServer.aggregatedDiscoveryServiceImpl)
                .addService(discoveryServer.clusterDiscoveryServiceImpl)
                .addService(discoveryServer.endpointDiscoveryServiceImpl)
                .addService(discoveryServer.listenerDiscoveryServiceImpl)
                .addService(discoveryServer.routeDiscoveryServiceImpl)
        }

        private fun NettyServerBuilder.withV3EnvoyServices(discoveryServer: V3DiscoveryServer): NettyServerBuilder {
            return this.addService(discoveryServer.aggregatedDiscoveryServiceImpl)
                .addService(discoveryServer.clusterDiscoveryServiceImpl)
                .addService(discoveryServer.endpointDiscoveryServiceImpl)
                .addService(discoveryServer.listenerDiscoveryServiceImpl)
                .addService(discoveryServer.routeDiscoveryServiceImpl)
        }

        private class ThreadNamingThreadFactory(val threadNamePrefix: String) : ThreadFactory {
            private val counter = AtomicInteger()
            override fun newThread(r: Runnable) = Thread(r, "$threadNamePrefix-${counter.getAndIncrement()}")
        }

        private fun newMeteredThreadPoolExecutor(
            corePoolSize: Int,
            maximumPoolSize: Int,
            keepAliveTimeMillis: Long,
            workQueue: BlockingQueue<Runnable>,
            poolExecutorName: String
        ): ThreadPoolExecutor {
            val threadPoolExecutor = ThreadPoolExecutor(
                corePoolSize,
                maximumPoolSize,
                keepAliveTimeMillis,
                TimeUnit.MILLISECONDS,
                workQueue,
                ThreadNamingThreadFactory(poolExecutorName)
            )
            meterExecutor(threadPoolExecutor, poolExecutorName)
            return threadPoolExecutor
        }

        private fun newMeteredFixedThreadPool(executorServiceName: String, poolSize: Int): ExecutorService {
            val executor = Executors.newFixedThreadPool(poolSize, ThreadNamingThreadFactory(executorServiceName))
            meterExecutor(executor, executorServiceName)
            return executor
        }

        private fun newMeteredCachedThreadPool(executorServiceName: String): ExecutorService {
            val executor = Executors.newCachedThreadPool(ThreadNamingThreadFactory(executorServiceName))
            meterExecutor(executor, executorServiceName)
            return executor
        }

        private fun meterExecutor(executor: ExecutorService, executorServiceName: String) {
            ExecutorServiceMetrics(executor, executorServiceName, executorServiceName, emptySet())
                .bindTo(meterRegistry)
        }

        private fun grpcServer(
            config: ServerProperties,
            v2discoveryServer: V2DiscoveryServer,
            v3discoveryServer: V3DiscoveryServer,
            nioEventLoopExecutor: Executor,
            grpcServerExecutor: Executor
        ): Server {
            val serverBuilder = NettyServerBuilder.forPort(config.port)
                .workerEventLoopGroup(
                    NioEventLoopGroup(
                        config.nioEventLoopThreadCount,
                        nioEventLoopExecutor
                    )
                )
                .executor(grpcServerExecutor)
                .keepAliveTime(config.netty.keepAliveTime.toMillis(), TimeUnit.MILLISECONDS)
                .permitKeepAliveTime(config.netty.permitKeepAliveTime.toMillis(), TimeUnit.MILLISECONDS)
                .permitKeepAliveWithoutCalls(config.netty.permitKeepAliveWithoutCalls)

            if (properties.envoy.snapshot.supportV2Configuration) {
                serverBuilder.withV2EnvoyServices(v2discoveryServer)
            }
            return serverBuilder
                .withV3EnvoyServices(v3discoveryServer)
                .build()
        }
    }
}
