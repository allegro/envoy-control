package pl.allegro.tech.servicemesh.envoycontrol.synchronization

import com.github.benmanes.caffeine.cache.Caffeine
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import pl.allegro.tech.servicemesh.envoycontrol.logger
import pl.allegro.tech.servicemesh.envoycontrol.services.ClusterState
import pl.allegro.tech.servicemesh.envoycontrol.services.Locality
import pl.allegro.tech.servicemesh.envoycontrol.services.MultiClusterState
import pl.allegro.tech.servicemesh.envoycontrol.services.MultiClusterState.Companion.toMultiClusterState
import pl.allegro.tech.servicemesh.envoycontrol.services.ServicesState
import pl.allegro.tech.servicemesh.envoycontrol.utils.CLUSTER_TAG
import pl.allegro.tech.servicemesh.envoycontrol.utils.CROSS_DC_SYNC_CANCELLED_METRIC
import pl.allegro.tech.servicemesh.envoycontrol.utils.CROSS_DC_SYNC_SECONDS_METRIC
import pl.allegro.tech.servicemesh.envoycontrol.utils.CROSS_DC_SYNC_TOTAL_METRIC
import pl.allegro.tech.servicemesh.envoycontrol.utils.OPERATION_TAG
import pl.allegro.tech.servicemesh.envoycontrol.utils.SERVICES_STATE_ERRORS_METRIC
import reactor.core.publisher.Flux
import reactor.core.publisher.FluxSink
import java.lang.Integer.max
import java.net.URI
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RemoteServices(
    private val controlPlaneClient: ControlPlaneClient,
    private val meterRegistry: MeterRegistry,
    private val controlPlaneInstanceFetcher: ControlPlaneInstanceFetcher,
    private val remoteClusters: List<String>,
    cacheDuration: Duration
) {
    private val logger by logger()
    private val clusterStateCache = Caffeine.newBuilder().expireAfterWrite(cacheDuration).build<String, ClusterState>()
    private val scheduler = Executors.newScheduledThreadPool(max(remoteClusters.size, 1))

    fun getChanges(interval: Long): Flux<MultiClusterState> {
        val aclFlux: Flux<MultiClusterState> = Flux.create({ sink ->
            scheduler.scheduleWithFixedDelay({
                meterRegistry.timer(
                    CROSS_DC_SYNC_SECONDS_METRIC,
                    Tags.of(OPERATION_TAG, "get-multi-cluster-state")
                )
                    .recordCallable {
                        getChanges(sink::next, interval)
                    }
            }, 0, interval, TimeUnit.SECONDS)
        }, FluxSink.OverflowStrategy.LATEST)
        return aclFlux.doOnCancel {
            meterRegistry.counter(CROSS_DC_SYNC_CANCELLED_METRIC).increment()
            logger.warn("Cancelling cross dc sync")
        }
    }

    private fun getChanges(stateConsumer: (MultiClusterState) -> Unit, interval: Long) {
        remoteClusters
            .map { cluster -> clusterWithControlPlaneInstances(cluster) }
            .filter { (_, instances) -> instances.isNotEmpty() }
            .map { (cluster, instances) -> getClusterState(instances, cluster, interval) }
            .mapNotNull { it.get() }
            .toMultiClusterState()
            .let { if (it.isNotEmpty()) stateConsumer(it) }
    }

    private fun getClusterState(
        instances: List<URI>,
        cluster: String,
        interval: Long
    ): CompletableFuture<ClusterState?> {
        return controlPlaneClient.getState(chooseInstance(instances))
            .thenApply { servicesStateFromCluster(cluster, it) }
            .orTimeout(interval, TimeUnit.SECONDS)
            .exceptionally {
                meterRegistry.counter(
                    SERVICES_STATE_ERRORS_METRIC,
                    Tags.of(
                        CLUSTER_TAG, cluster,
                        OPERATION_TAG, "get-state"
                    )
                ).increment()
                logger.warn("Error synchronizing instances ${it.message}", it)
                clusterStateCache.getIfPresent(cluster)
            }
    }

    private fun clusterWithControlPlaneInstances(cluster: String): Pair<String, List<URI>> {
        return try {
            val instances = controlPlaneInstanceFetcher.instances(cluster)
            cluster to instances
        } catch (e: Exception) {
            meterRegistry.counter(
                SERVICES_STATE_ERRORS_METRIC,
                Tags.of(
                    CLUSTER_TAG, cluster,
                    OPERATION_TAG, "get-instances"
                )
            ).increment()
            logger.warn("Failed fetching instances from $cluster", e)
            cluster to emptyList()
        }
    }

    private fun servicesStateFromCluster(
        cluster: String,
        state: ServicesState
    ): ClusterState {
        meterRegistry.counter(
            CROSS_DC_SYNC_TOTAL_METRIC, Tags.of(CLUSTER_TAG, cluster)
        )
            .increment()
        val clusterState = ClusterState(
            state.removeServicesWithoutInstances(),
            Locality.REMOTE,
            cluster
        )
        clusterStateCache.put(cluster, clusterState)
        return clusterState
    }

    private fun chooseInstance(serviceInstances: List<URI>): URI = serviceInstances.random()
}
