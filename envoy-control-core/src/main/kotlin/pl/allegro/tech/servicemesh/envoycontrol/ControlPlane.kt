package pl.allegro.tech.servicemesh.envoycontrol

import io.envoyproxy.controlplane.cache.NodeGroup
import io.envoyproxy.controlplane.cache.SimpleCache
import io.envoyproxy.controlplane.cache.SnapshotCache
import io.envoyproxy.controlplane.server.DefaultExecutorGroup
import io.envoyproxy.controlplane.server.DiscoveryServer
import io.envoyproxy.controlplane.server.ExecutorGroup
import io.envoyproxy.controlplane.server.callback.SnapshotCollectingCallback
import io.grpc.Server
import io.grpc.netty.NettyServerBuilder
import io.micrometer.core.instrument.MeterRegistry
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
import pl.allegro.tech.servicemesh.envoycontrol.services.LocalityAwareServicesState
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.listeners.filters.EnvoyHttpFilters
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotUpdater
import reactor.core.Disposable
import reactor.core.publisher.Flux
import reactor.core.scheduler.Schedulers
import java.time.Clock
import java.util.concurrent.Executor
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
    private val changes: Flux<List<LocalityAwareServicesState>>
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
        var updateSnapshotExecutor: Executor? = null
        var metrics: EnvoyControlMetrics = DefaultEnvoyControlMetrics()
        var envoyHttpFilters: EnvoyHttpFilters = EnvoyHttpFilters.emptyFilters

        var nodeGroup: NodeGroup<Group> = MetadataNodeGroup(
            properties = properties.envoy.snapshot
        )

        fun build(changes: Flux<List<LocalityAwareServicesState>>): ControlPlane {
            if (grpcServerExecutor == null) {
                grpcServerExecutor = ThreadPoolExecutor(
                    properties.server.serverPoolSize,
                    properties.server.serverPoolSize,
                    properties.server.serverPoolKeepAlive.toMillis(), TimeUnit.MILLISECONDS,
                    LinkedBlockingQueue<Runnable>(),
                    ThreadNamingThreadFactory("grpc-server-worker")
                )
            }

            if (nioEventLoopExecutor == null) {
                // unbounded executor - netty will only use configured number of threads
                // (by nioEventLoopThreadCount property or default netty value: <number of CPUs> * 2)
                nioEventLoopExecutor = Executors.newCachedThreadPool(
                    ThreadNamingThreadFactory("grpc-worker-event-loop")
                )
            }

            if (executorGroup == null) {
                executorGroup = when (properties.server.executorGroup.type) {
                    ExecutorType.DIRECT -> DefaultExecutorGroup()
                    ExecutorType.PARALLEL -> {
                        val executor = Executors.newFixedThreadPool(
                            properties.server.executorGroup.parallelPoolSize,
                            ThreadNamingThreadFactory("discovery-responses-executor")
                        )
                        ExecutorGroup { executor }
                    }
                }
            }

            if (updateSnapshotExecutor == null) {
                updateSnapshotExecutor = Executors.newSingleThreadExecutor(ThreadNamingThreadFactory("snapshot-update"))
            }

            val cache = SimpleCache(nodeGroup)

            val cleanupProperties = properties.server.snapshotCleanup

            val groupChangeWatcher = GroupChangeWatcher(cache, metrics)

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
                CachedProtoResourcesSerializer()
            )

            return ControlPlane(
                grpcServer(properties.server, discoveryServer, nioEventLoopExecutor!!, grpcServerExecutor!!),
                SnapshotUpdater(
                    cache,
                    properties.envoy.snapshot,
                    Schedulers.fromExecutor(updateSnapshotExecutor!!),
                    groupChangeWatcher.onGroupAdded(),
                    meterRegistry,
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

        fun withUpdateSnapshotExecutor(executor: Executor): ControlPlaneBuilder {
            updateSnapshotExecutor = executor
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
