package pl.allegro.tech.servicemesh.envoycontrol.synchronization

import pl.allegro.tech.servicemesh.envoycontrol.EnvoyControlProperties
import pl.allegro.tech.servicemesh.envoycontrol.services.MultiClusterState
import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceChanges
import reactor.core.publisher.Flux

// TODO(dj): #110 rename to RemoteClusterStateChanges
class CrossDcServiceChanges(
    val properties: EnvoyControlProperties,
    private val crossDcService: CrossDcServices
) : ServiceChanges {
    override fun stream(): Flux<MultiClusterState> =
        crossDcService
            .getChanges(properties.sync.pollingInterval)
            .startWith(MultiClusterState.empty())
            .distinctUntilChanged()
            .name("cross-dc-changes-distinct").metrics()
}
