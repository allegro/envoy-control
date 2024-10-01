package pl.allegro.tech.servicemesh.envoycontrol.synchronization

import pl.allegro.tech.servicemesh.envoycontrol.EnvoyControlProperties
import pl.allegro.tech.servicemesh.envoycontrol.services.ClusterStateChanges
import pl.allegro.tech.servicemesh.envoycontrol.services.MultiClusterState
import reactor.core.publisher.Flux

class RemoteClusterStateChanges(
    val properties: EnvoyControlProperties,
    private val remoteServices: RemoteServices
) : ClusterStateChanges {
    override fun stream(): Flux<MultiClusterState> =
        remoteServices
            .getChanges(properties.sync.pollingInterval)
            .startWith(MultiClusterState.empty())
            .distinctUntilChanged()
            .name("cross.dc.synchronization.distinct").metrics()
}
