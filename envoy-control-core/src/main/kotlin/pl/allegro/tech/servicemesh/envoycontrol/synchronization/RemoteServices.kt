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
import reactor.core.publisher.Mono
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

    private val scheduler = Executors.newScheduledThreadPool(1)
    private val newScheduledThreadPool = Executors.newScheduledThreadPool(remoteClusters.size)

    fun getChanges(interval: Long): Flux<MultiClusterState> {

        return Flux.create({ sink ->
            scheduler.scheduleWithFixedDelay({
                remoteClusters
                    .map { cluster -> clusterWithControlPlaneInstances(cluster) }
                    .filter { (_, instances) -> instances.isNotEmpty() }
                    .map { (cluster, instances) -> servicesStateFromCluster(cluster, instances) }
                    .toMultiClusterState()
                    .apply { sink.next(this) }
            }, 0, interval, TimeUnit.SECONDS)
        }, FluxSink.OverflowStrategy.LATEST)

        //scheduler - $interval
        //parallel requests
        //group
        //sink

        //1 thread for scheduler
        //n thread per dc

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
    ): ClusterState {
        val instance = chooseInstance(instances)
        return Mono.fromCallable {
            controlPlaneClient.getState(instance)
        }
            .checkpoint("cross-dc-service-update-$cluster")
            .name("cross-dc-service-update-$cluster").metrics()
            .map { ServicesState(it.getOnlyServicesWithInstances().toMutableMap()) }
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

    private fun chooseInstance(serviceInstances: List<URI>): URI = serviceInstances.random()
}
