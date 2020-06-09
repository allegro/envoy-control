package pl.allegro.tech.servicemesh.envoycontrol.synchronization

import pl.allegro.tech.servicemesh.envoycontrol.EnvoyControlProperties
import pl.allegro.tech.servicemesh.envoycontrol.services.MultiZoneState
import pl.allegro.tech.servicemesh.envoycontrol.services.ZoneStateChanges
import reactor.core.publisher.Flux

class RemoteZoneStateChanges(
    val properties: EnvoyControlProperties,
    private val remoteServices: RemoteServices
) : ZoneStateChanges {
    override fun stream(): Flux<MultiZoneState> =
        remoteServices
            .getChanges(properties.sync.pollingInterval)
            .startWith(MultiZoneState.empty())
            .distinctUntilChanged()
            .name("cross-dc-changes-distinct").metrics()
}
