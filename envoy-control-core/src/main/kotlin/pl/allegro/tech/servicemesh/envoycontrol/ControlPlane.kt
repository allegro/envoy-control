package pl.allegro.tech.servicemesh.envoycontrol

import io.envoyproxy.controlplane.cache.NodeGroup
import io.envoyproxy.controlplane.cache.SnapshotCache
import io.envoyproxy.controlplane.server.DefaultExecutorGroup
import io.envoyproxy.controlplane.server.DiscoveryServer
import io.envoyproxy.controlplane.server.ExecutorGroup
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
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.EnvoyListenersFactory
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters.AccessLogFilterFactory
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters.EnvoyHttpFilters
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.routes.ServiceTagMetadataGenerator
import pl.allegro.tech.servicemesh.envoycontrol.utils.DirectScheduler
import pl.allegro.tech.servicemesh.envoycontrol.utils.ParallelScheduler
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
    val cache: SnapshotCache<Group>,
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
                grpcServerExecutor = meterThreadPoolExecutor(
                    properties.server.serverPoolSize,
                    properties.server.serverPoolSize,
                    properties.server.serverPoolKeepAlive.toMillis(),
                    LinkedBlockingQueue<Runnable>(),
                    "grpc-server-worker"
                )
            }

            if (nioEventLoopExecutor == null) {
                // unbounded executor - netty will only use configured number of threads
                // (by nioEventLoopThreadCount property or default netty value: <number of CPUs> * 2)
                nioEventLoopExecutor = newMeterCachedThreadPool("grpc-worker-event-loop")
            }

            if (executorGroup == null) {
                executorGroup = when (properties.server.executorGroup.type) {
                    ExecutorType.DIRECT -> DefaultExecutorGroup()
                    ExecutorType.PARALLEL -> {
                        // TODO(https://github.com/allegro/envoy-control/issues/103) this implementation of parallel
                        //   executor group is invalid, because it may lead to sending XDS responses out of order for
                        //   given DiscoveryRequestStreamObserver. We should switch to multiple, single-threaded
                        //   ThreadPoolExecutors. More info in linked task.
                        val executor = newMeterFixedThreadPool(
                            "discovery-responses-executor",
                            properties.server.executorGroup.parallelPoolSize
                        )
                        ExecutorGroup { executor }
                    }
                }
            }

            if (globalSnapshotExecutor == null) {
                globalSnapshotExecutor = newMeterFixedThreadPool(
                    "snapshot-update",
                    properties.server.globalSnapshotUpdatePoolSize
                )
            }

            val groupSnapshotProperties = properties.server.groupSnapshotUpdateScheduler

            val groupSnapshotScheduler = when (groupSnapshotProperties.type) {
                ExecutorType.DIRECT -> DirectScheduler
                ExecutorType.PARALLEL -> ParallelScheduler(
                    scheduler = Schedulers.fromExecutor(
                        groupSnapshotParallelExecutorSupplier()
                            ?: newMeterFixedThreadPool(
                                "group-snapshot",
                                groupSnapshotProperties.parallelPoolSize
                            )
                    ),
                    parallelism = groupSnapshotProperties.parallelPoolSize
                )
            }

            val cache = SimpleCache(nodeGroup, properties.envoy.snapshot.shouldSendMissingEndpoints)

            val cleanupProperties = properties.server.snapshotCleanup

            val groupChangeWatcher = GroupChangeWatcher(cache, metrics, meterRegistry)

            val discoveryServer = DiscoveryServer(
                listOf(
                    CompositeDiscoveryServerCallbacks(
                        meterRegistry,
                        SnapshotCollectingCallback(
                            cache,
                            nodeGroup,
                            Clock.systemDefaultZone(),
                            emptySet(),
                            cleanupProperties.collectAfterMillis.toMillis(),
                            cleanupProperties.collectionIntervalMillis.toMillis()
                        ),
                        LoggingDiscoveryServerCallbacks(
                            properties.server.logFullRequest,
                            properties.server.logFullResponse
                        ),
                        MeteredConnectionsCallbacks().also {
                            meterRegistry.gauge("grpc.all-connections", it.connections)
                            MeteredConnectionsCallbacks.MetricsStreamType.values().map { type ->
                                meterRegistry.gauge("grpc.connections.${type.name.toLowerCase()}", it.connections(type))
                            }
                        },
                        NodeMetadataValidator(properties.envoy.snapshot)
                    )
                ),
                groupChangeWatcher,
                executorGroup,
                CachedProtoResourcesSerializer(meterRegistry, properties.server.reportProtobufCacheMetrics)
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
                grpcServer(properties.server, discoveryServer, nioEventLoopExecutor!!, grpcServerExecutor!!),
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

        private fun NettyServerBuilder.withEnvoyServices(discoveryServer: DiscoveryServer): NettyServerBuilder =
            this.addService(discoveryServer.aggregatedDiscoveryServiceImpl)
                .addService(discoveryServer.clusterDiscoveryServiceImpl)
                .addService(discoveryServer.endpointDiscoveryServiceImpl)
                .addService(discoveryServer.listenerDiscoveryServiceImpl)
                .addService(discoveryServer.routeDiscoveryServiceImpl)

        private class ThreadNamingThreadFactory(val threadNamePrefix: String) : ThreadFactory {
            private val counter = AtomicInteger()
            override fun newThread(r: Runnable) = Thread(r, "$threadNamePrefix-${counter.getAndIncrement()}")
        }

        private fun meterThreadPoolExecutor(
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

        private fun newMeterFixedThreadPool(executorServiceName: String, poolSize: Int): ExecutorService {
            val executor = Executors.newFixedThreadPool(poolSize, ThreadNamingThreadFactory(executorServiceName))
            meterExecutor(executor, executorServiceName)
            return executor
        }

        private fun newMeterCachedThreadPool(executorServiceName: String): ExecutorService {
            val executor = Executors.newCachedThreadPool(ThreadNamingThreadFactory(executorServiceName))
            meterExecutor(executor, executorServiceName)
            return executor
        }

        private fun meterExecutor(executor: ExecutorService, executorServiceName: String) {
            ExecutorServiceMetrics(executor, executorServiceName, "executor", emptySet())
                .bindTo(meterRegistry)
        }

        private fun grpcServer(
            config: ServerProperties,
            discoveryServer: DiscoveryServer,
            nioEventLoopExecutor: Executor,
            grpcServerExecutor: Executor
        ): Server = NettyServerBuilder.forPort(config.port)
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
            .withEnvoyServices(discoveryServer)
            .build()
    }
}
