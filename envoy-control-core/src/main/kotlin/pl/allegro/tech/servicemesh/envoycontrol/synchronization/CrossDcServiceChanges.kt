package pl.allegro.tech.servicemesh.envoycontrol.synchronization

import pl.allegro.tech.servicemesh.envoycontrol.EnvoyControlProperties
import pl.allegro.tech.servicemesh.envoycontrol.services.LocalityAwareServicesState
import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceChanges
import reactor.core.publisher.Flux

class CrossDcServiceChanges(
    val properties: EnvoyControlProperties,
    val crossDcService: CrossDcServices
) : ServiceChanges {
    override fun stream(): Flux<Set<LocalityAwareServicesState>> =
        crossDcService
            .getChanges(properties.sync.pollingInterval)
            .startWith(emptySet<LocalityAwareServicesState>())
            .distinctUntilChanged()
            .name("cross-dc-changes-distinct").metrics()
}
