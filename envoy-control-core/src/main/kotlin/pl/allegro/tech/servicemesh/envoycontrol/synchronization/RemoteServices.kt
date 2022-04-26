package pl.allegro.tech.servicemesh.envoycontrol.synchronization

import io.micrometer.core.instrument.MeterRegistry
import pl.allegro.tech.servicemesh.envoycontrol.logger
import pl.allegro.tech.servicemesh.envoycontrol.services.ClusterState
import pl.allegro.tech.servicemesh.envoycontrol.services.Locality
import pl.allegro.tech.servicemesh.envoycontrol.services.MultiClusterState
import pl.allegro.tech.servicemesh.envoycontrol.services.MultiClusterState.Companion.toMultiClusterState
import reactor.core.publisher.Flux
import reactor.core.publisher.FluxSink
import java.net.URI
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
                //TODO meter execution time for each run
                getChanges(sink::next)
            }, 0, interval, TimeUnit.SECONDS)
        }, FluxSink.OverflowStrategy.LATEST)
    }

    private fun getChanges(sink: (MultiClusterState) -> Unit) {
        remoteClusters
            .map { cluster -> clusterWithControlPlaneInstances(cluster) }
            .filter { (_, instances) -> instances.isNotEmpty() }
            .mapNotNull { (cluster, instances) -> servicesStateFromCluster(cluster, instances) }
            .toMultiClusterState()
            .let { if (it.isNotEmpty()) sink(it) }
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
        instances: List<URI>
    ): ClusterState? {
        val instance = chooseInstance(instances)

        return try {
            meterRegistry.counter("cross-dc-service-update-$cluster").increment()
            val clusterState = ClusterState(
                controlPlaneClient.getState(instance).removeServicesWithoutInstances(),
                Locality.REMOTE,
                cluster
            )
            clusterStateCache += cluster to clusterState
            clusterState
        } catch (exception: Exception) {
            meterRegistry.counter("cross-dc-synchronization.$cluster.state-fetcher.errors").increment()
            logger.warn("Error synchronizing instances ${exception.message}", exception)
            clusterStateCache[cluster]
        }
    }

    private fun chooseInstance(serviceInstances: List<URI>): URI = serviceInstances.random()
}
