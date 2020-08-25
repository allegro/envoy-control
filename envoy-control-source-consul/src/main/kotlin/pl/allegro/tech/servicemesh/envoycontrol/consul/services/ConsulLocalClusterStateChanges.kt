package pl.allegro.tech.servicemesh.envoycontrol.consul.services

import pl.allegro.tech.servicemesh.envoycontrol.services.LocalClusterStateChanges
import pl.allegro.tech.servicemesh.envoycontrol.services.Locality
import pl.allegro.tech.servicemesh.envoycontrol.services.ClusterState
import pl.allegro.tech.servicemesh.envoycontrol.services.MultiClusterState
import pl.allegro.tech.servicemesh.envoycontrol.services.MultiClusterState.Companion.toMultiClusterState
import pl.allegro.tech.servicemesh.envoycontrol.services.ServicesState
import pl.allegro.tech.servicemesh.envoycontrol.services.transformers.ServiceInstancesTransformer
import reactor.core.publisher.Flux
import java.util.concurrent.atomic.AtomicReference

class ConsulLocalClusterStateChanges(
    private val consulChanges: ConsulServiceChanges,
    private val locality: Locality,
    private val cluster: String,
    private val transformers: List<ServiceInstancesTransformer> = emptyList(),
    override val latestServiceState: AtomicReference<ServicesState> = AtomicReference(ServicesState())
) : LocalClusterStateChanges {
    override fun stream(): Flux<MultiClusterState> =
        consulChanges
            .watchState()
            .map { state ->
                transformers
                    .fold(state.allInstances().asSequence()) { instancesSequence, transformer ->
                        transformer.transform(instancesSequence)
                    }
                    .sortedBy { it.serviceName }
                    .associateBy { it.serviceName }
                    .let(::ServicesState)
            }
            .doOnNext { latestServiceState.set(it) }
            .map {
                ClusterState(it, locality, cluster).toMultiClusterState()
            }

    override fun isInitialStateLoaded(): Boolean = latestServiceState.get() != ServicesState()
}
