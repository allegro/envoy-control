package pl.allegro.tech.servicemesh.envoycontrol.synchronization

import pl.allegro.tech.servicemesh.envoycontrol.services.LocalityAwareServicesState
import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceChanges
import reactor.core.publisher.Flux

class GlobalServiceChanges(
    private val serviceChanges: Array<ServiceChanges>
) {
    fun combined(): Flux<List<LocalityAwareServicesState>> {
        val serviceStatesStreams: List<Flux<Set<LocalityAwareServicesState>>> = serviceChanges.map { it.stream() }

        return Flux.combineLatest(serviceStatesStreams) { statesArray ->
            (statesArray.asSequence() as Sequence<List<LocalityAwareServicesState>>)
                .flatten()
                .toList()
        }
            .checkpoint("global-service-changes-emitted")
            .name("global-service-changes-emitted").metrics()
    }
}
