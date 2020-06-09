package pl.allegro.tech.servicemesh.envoycontrol.synchronization

import io.micrometer.core.instrument.MeterRegistry
import pl.allegro.tech.servicemesh.envoycontrol.logger
import pl.allegro.tech.servicemesh.envoycontrol.services.ZoneState
import pl.allegro.tech.servicemesh.envoycontrol.services.Locality
import pl.allegro.tech.servicemesh.envoycontrol.services.MultiZoneState
import pl.allegro.tech.servicemesh.envoycontrol.services.MultiZoneState.Companion.toMultiZoneState
import pl.allegro.tech.servicemesh.envoycontrol.utils.measureBuffer
import pl.allegro.tech.servicemesh.envoycontrol.utils.measureDiscardedItems
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.net.URI
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

class RemoteServices(
    private val controlPlaneClient: AsyncControlPlaneClient,
    private val meterRegistry: MeterRegistry,
    private val controlPlaneInstanceFetcher: ControlPlaneInstanceFetcher,
    private val remoteZones: List<String>
) {
    private val logger by logger()
    private val zoneStateCache = ConcurrentHashMap<String, ZoneState>()

    fun getChanges(interval: Long): Flux<MultiZoneState> {
        return Flux
            .interval(Duration.ofSeconds(0), Duration.ofSeconds(interval))
            .checkpoint("cross-dc-services-ticks")
            .name("cross-dc-services-ticks").metrics()
            // Cross zone sync is not a backpressure compatible stream.
            // If running cross zone sync is slower than interval we have to drop interval events
            // and run another cross zone on another interval tick.
            .onBackpressureDrop()
            .measureDiscardedItems("cross-dc-services-ticks", meterRegistry)
            .checkpoint("cross-dc-services-update-requested")
            .name("cross-dc-services-update-requested").metrics()
            .flatMap {
                Flux.fromIterable(remoteZones)
                    .map { zone -> zoneWithControlPlaneInstances(zone) }
                    .filter { (_, instances) -> instances.isNotEmpty() }
                    .flatMap { (zone, instances) -> servicesStateFromZone(zone, instances) }
                    .collectList()
                    .map { it.toMultiZoneState() }
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

    private fun zoneWithControlPlaneInstances(zone: String): Pair<String, List<URI>> {
        return try {
            val instances = controlPlaneInstanceFetcher.instances(zone)
            zone to instances
        } catch (e: Exception) {
            meterRegistry.counter("cross-dc-synchronization.$zone.instance-fetcher.errors").increment()
            logger.warn("Failed fetching instances from $zone", e)
            zone to emptyList()
        }
    }

    private fun servicesStateFromZone(
        zone: String,
        instances: List<URI>
    ): Mono<ZoneState> {
        val instance = chooseInstance(instances)
        return controlPlaneClient
            .getState(instance)
            .checkpoint("cross-dc-service-update-$zone")
            .name("cross-dc-service-update-$zone").metrics()
            .map {
                ZoneState(it, Locality.REMOTE, zone)
            }
            .doOnSuccess {
                zoneStateCache += zone to it
            }
            .onErrorResume { exception ->
                // TODO(dj): #110 cross-dc- naming stays for now to avoid breaking existing monitoring,
                //  but at a bigger release we could tackle it
                meterRegistry.counter("cross-dc-synchronization.$zone.state-fetcher.errors").increment()
                logger.warn("Error synchronizing instances ${exception.message}", exception)
                Mono.justOrEmpty(zoneStateCache[zone])
            }
    }

    private fun chooseInstance(serviceInstances: List<URI>): URI = serviceInstances.random()
}
