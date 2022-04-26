package pl.allegro.tech.servicemesh.envoycontrol.synchronization

import io.micrometer.core.instrument.MeterRegistry
import pl.allegro.tech.servicemesh.envoycontrol.logger
import pl.allegro.tech.servicemesh.envoycontrol.services.ClusterState
import pl.allegro.tech.servicemesh.envoycontrol.services.Locality
import pl.allegro.tech.servicemesh.envoycontrol.services.MultiClusterState
import pl.allegro.tech.servicemesh.envoycontrol.services.MultiClusterState.Companion.toMultiClusterState
import pl.allegro.tech.servicemesh.envoycontrol.services.ServicesState
import reactor.core.publisher.Flux
import reactor.core.publisher.FluxSink
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RemoteServices(
    private val controlPlaneClient: ControlPlaneClient,
    private val meterRegistry: MeterRegistry,
    private val controlPlaneInstanceFetcher: ControlPlaneInstanceFetcher,
    private val remoteClusters: List<String>
) {
    private val logger by logger()
    private val clusterStateCache = ConcurrentHashMap<String, ClusterState>()
    private val scheduler = Executors.newScheduledThreadPool(remoteClusters.size)

    fun getChanges(interval: Long): Flux<MultiClusterState> {
        return Flux.create({ sink ->
            scheduler.scheduleWithFixedDelay({
                meterRegistry.timer("sync-dc.get-multi-cluster-states.time").record {
                    getChanges(sink::next, interval)
                }
            }, 0, interval, TimeUnit.SECONDS)
        }, FluxSink.OverflowStrategy.LATEST)
    }

    private fun getChanges(sink: (MultiClusterState) -> Unit, interval: Long) {
        remoteClusters
            .map { cluster -> clusterWithControlPlaneInstances(cluster) }
            .filter { (_, instances) -> instances.isNotEmpty() }
            .map { (cluster, instances) -> getClusterState(instances, cluster) }
            .mapNotNull { it.get(interval, TimeUnit.SECONDS) }
            .toMultiClusterState()
            .let { if (it.isNotEmpty()) sink(it) }
    }

    private fun getClusterState(
        instances: List<URI>,
        cluster: String
    ): CompletableFuture<ClusterState?> {
        return controlPlaneClient.getState(chooseInstance(instances))
            .thenApply { servicesStateFromCluster(cluster, it) }
            .exceptionally {
                meterRegistry.counter("cross-dc-synchronization.$cluster.state-fetcher.errors").increment()
                logger.warn("Error synchronizing instances ${it.message}", it)
                clusterStateCache[cluster]
            }
    }

    private fun clusterWithControlPlaneInstances(cluster: String): Pair<String, List<URI>> {
        return try {
            val instances = controlPlaneInstanceFetcher.instances(cluster)
            cluster to instances
        } catch (e: Exception) {
            meterRegistry.counter("cross-dc-synchronization.$cluster.instance-fetcher.errors").increment()
            logger.warn("Failed fetching instances from $cluster", e)
            cluster to emptyList()
        }
    }

    private fun servicesStateFromCluster(
        cluster: String,
        state: ServicesState
    ): ClusterState {
        meterRegistry.counter("cross-dc-service-update-$cluster").increment()
        val clusterState = ClusterState(
            state.removeServicesWithoutInstances(),
            Locality.REMOTE,
            cluster
        )
        clusterStateCache += cluster to clusterState
        return clusterState
    }

    private fun chooseInstance(serviceInstances: List<URI>): URI = serviceInstances.random()
}
