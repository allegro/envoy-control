package pl.allegro.tech.servicemesh.envoycontrol.synchronization

import pl.allegro.tech.servicemesh.envoycontrol.EnvoyControlProperties
import pl.allegro.tech.servicemesh.envoycontrol.services.ClusterStateChanges
import pl.allegro.tech.servicemesh.envoycontrol.services.MultiClusterState
import pl.allegro.tech.servicemesh.envoycontrol.utils.METRIC_EMITTER_TAG
import pl.allegro.tech.servicemesh.envoycontrol.utils.REACTOR_METRIC
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
            .name(REACTOR_METRIC)
            .tag(METRIC_EMITTER_TAG, "cross-dc-synchronisation")
            .metrics()
}
