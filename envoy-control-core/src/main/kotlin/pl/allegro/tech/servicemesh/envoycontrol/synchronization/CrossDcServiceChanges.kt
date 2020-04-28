package pl.allegro.tech.servicemesh.envoycontrol.synchronization

import pl.allegro.tech.servicemesh.envoycontrol.EnvoyControlProperties
import pl.allegro.tech.servicemesh.envoycontrol.services.LocalityAwareServicesState
import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceChanges
import reactor.core.publisher.Flux

class CrossDcServiceChanges(
    val properties: EnvoyControlProperties,
    private val crossDcService: CrossDcServices
) : ServiceChanges {
    override fun stream(): Flux<List<LocalityAwareServicesState>> =
        crossDcService
            .getChanges(properties.sync.pollingInterval)
            .startWith(emptyList<LocalityAwareServicesState>())
            .distinctUntilChanged()
            .name("cross-dc-changes-distinct").metrics()
}
