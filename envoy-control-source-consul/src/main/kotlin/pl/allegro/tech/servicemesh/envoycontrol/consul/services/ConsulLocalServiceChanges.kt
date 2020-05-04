package pl.allegro.tech.servicemesh.envoycontrol.consul.services

import pl.allegro.tech.servicemesh.envoycontrol.services.LocalServiceChanges
import pl.allegro.tech.servicemesh.envoycontrol.services.Locality
import pl.allegro.tech.servicemesh.envoycontrol.services.ClusterState
import pl.allegro.tech.servicemesh.envoycontrol.services.MultiClusterState
import pl.allegro.tech.servicemesh.envoycontrol.services.MultiClusterState.Companion.toMultiClusterState
import pl.allegro.tech.servicemesh.envoycontrol.services.ServicesState
import pl.allegro.tech.servicemesh.envoycontrol.services.transformers.ServiceInstancesTransformer
import reactor.core.publisher.Flux
import java.util.concurrent.atomic.AtomicReference

class ConsulLocalServiceChanges(
    private val consulChanges: ConsulServiceChanges,
    private val locality: Locality,
    private val localDc: String,
    private val transformers: List<ServiceInstancesTransformer> = emptyList(),
    override val latestServiceState: AtomicReference<ServicesState> = AtomicReference(ServicesState())
) : LocalServiceChanges {
    override fun stream(): Flux<MultiClusterState> =
        consulChanges
            .watchState()
            .map { state ->
                transformers
                    .fold(state.allInstances().asSequence()) { instancesSequence, transformer ->
                        transformer.transform(instancesSequence)
                    }
                    .associateBy { it.serviceName }
                    .let(::ServicesState)
            }
            .doOnNext { latestServiceState.set(it) }
            .map {
                ClusterState(it, locality, localDc).toMultiClusterState()
            }

    override fun isServiceStateLoaded(): Boolean = latestServiceState.get() != ServicesState()
}
