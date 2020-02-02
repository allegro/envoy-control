package pl.allegro.tech.servicemesh.envoycontrol.synchronization

import io.micrometer.core.instrument.MeterRegistry
import pl.allegro.tech.servicemesh.envoycontrol.services.LocalityAwareServicesState
import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceChanges
import pl.allegro.tech.servicemesh.envoycontrol.utils.measureBuffer
import pl.allegro.tech.servicemesh.envoycontrol.utils.measureDiscardedItems
import reactor.core.publisher.Flux

class GlobalServiceChanges(
    private val serviceChanges: Array<ServiceChanges>,
    private val meterRegistry: MeterRegistry
) {
    fun combined(): Flux<List<LocalityAwareServicesState>> {
        val serviceStatesStreams: List<Flux<Set<LocalityAwareServicesState>>> = serviceChanges.map { it.stream() }

        return Flux.combineLatest(serviceStatesStreams) { statesArray ->
            (statesArray.asSequence() as Sequence<List<LocalityAwareServicesState>>)
                .flatten()
                .toList()
        }
            .measureBuffer("global-service-changes-combine-latest", meterRegistry)
            .checkpoint("global-service-changes-emitted")
            .name("global-service-changes-emitted").metrics()
            .measureDiscardedItems("global-service-changes-end", meterRegistry) //TODO: remove
    }
}
