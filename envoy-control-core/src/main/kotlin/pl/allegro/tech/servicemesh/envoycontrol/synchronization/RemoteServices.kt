package pl.allegro.tech.servicemesh.envoycontrol.synchronization

import io.micrometer.core.instrument.MeterRegistry
import pl.allegro.tech.servicemesh.envoycontrol.logger
import pl.allegro.tech.servicemesh.envoycontrol.services.ClusterState
import pl.allegro.tech.servicemesh.envoycontrol.services.Locality
import pl.allegro.tech.servicemesh.envoycontrol.services.MultiClusterState
import pl.allegro.tech.servicemesh.envoycontrol.services.MultiClusterState.Companion.toMultiClusterState
import pl.allegro.tech.servicemesh.envoycontrol.services.ServicesState
import pl.allegro.tech.servicemesh.envoycontrol.utils.measureBuffer
import pl.allegro.tech.servicemesh.envoycontrol.utils.measureDiscardedItems
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.net.URI
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

class RemoteServices(
    private val controlPlaneClient: ControlPlaneClient,
    private val meterRegistry: MeterRegistry,
    private val controlPlaneInstanceFetcher: ControlPlaneInstanceFetcher,
    private val remoteClusters: List<String>
) {
    private val logger by logger()
    private val clusterStateCache = ConcurrentHashMap<String, ClusterState>()

    fun getChanges(interval: Long): Flux<MultiClusterState> {
        return Flux
            .interval(Duration.ofSeconds(0), Duration.ofSeconds(interval))
            .checkpoint("cross-dc-services-ticks")
            .name("cross-dc-services-ticks").metrics()
            // Cross cluster sync is not a backpressure compatible stream.
            // If running cross cluster sync is slower than interval we have to drop interval events
            // and run another cross cluster on another interval tick.
            .onBackpressureDrop()
            .measureDiscardedItems("cross-dc-services-ticks", meterRegistry)
            .checkpoint("cross-dc-services-update-requested")
            .name("cross-dc-services-update-requested").metrics()
            .flatMap {
                Flux.fromIterable(remoteClusters)
                    .map { cluster -> clusterWithControlPlaneInstances(cluster) }
                    .filter { (_, instances) -> instances.isNotEmpty() }
                    .flatMap { (cluster, instances) -> servicesStateFromCluster(cluster, instances) }
                    .collectList()
                    .map { it.toMultiClusterState() }
            }
            .measureBuffer("cross-dc-services-flat-map", meterRegistry)
            .filter {
                it.isNotEmpty()
            }
            .doOnCancel {
                meterRegistry.counter("cross-dc-synchronization.cancelled").increment()
                logger.warn("Cancelling cross dc sync")
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
        instances: List<URI>
    ): Mono<ClusterState> {
        val instance = chooseInstance(instances)
        return Mono.fromCallable {
            controlPlaneClient.getState(instance)
        }
            .checkpoint("cross-dc-service-update-$cluster")
            .name("cross-dc-service-update-$cluster").metrics()
            .map { onlyServicesWithInstances(it) }
            .map {
                ClusterState(it, Locality.REMOTE, cluster)
            }
            .doOnSuccess {
                clusterStateCache += cluster to it
            }
            .onErrorResume { exception ->
                // TODO(dj): #110 cross-dc- naming stays for now to avoid breaking existing monitoring,
                //  but at a bigger release we could tackle it
                meterRegistry.counter("cross-dc-synchronization.$cluster.state-fetcher.errors").increment()
                logger.warn("Error synchronizing instances ${exception.message}", exception)
                Mono.justOrEmpty(clusterStateCache[cluster])
            }
    }

    private fun onlyServicesWithInstances(it: ServicesState): ServicesState =
        ServicesState(it.serviceNameToInstances.filterValues { value -> value.instances.isNotEmpty() }.toMutableMap())

    private fun chooseInstance(serviceInstances: List<URI>): URI = serviceInstances.random()
}
