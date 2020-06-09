package pl.allegro.tech.servicemesh.envoycontrol.consul.services

import pl.allegro.tech.servicemesh.envoycontrol.services.LocalZoneStateChanges
import pl.allegro.tech.servicemesh.envoycontrol.services.Locality
import pl.allegro.tech.servicemesh.envoycontrol.services.ZoneState
import pl.allegro.tech.servicemesh.envoycontrol.services.MultiZoneState
import pl.allegro.tech.servicemesh.envoycontrol.services.MultiZoneState.Companion.toMultiZoneState
import pl.allegro.tech.servicemesh.envoycontrol.services.ServicesState
import pl.allegro.tech.servicemesh.envoycontrol.services.transformers.ServiceInstancesTransformer
import reactor.core.publisher.Flux
import java.util.concurrent.atomic.AtomicReference

class ConsulLocalZoneStateChanges(
    private val consulChanges: ConsulServiceChanges,
    private val locality: Locality,
    private val zone: String,
    private val transformers: List<ServiceInstancesTransformer> = emptyList(),
    override val latestServiceState: AtomicReference<ServicesState> = AtomicReference(ServicesState())
) : LocalZoneStateChanges {
    override fun stream(): Flux<MultiZoneState> =
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
                ZoneState(it, locality, zone).toMultiZoneState()
            }

    override fun isInitialStateLoaded(): Boolean = latestServiceState.get() != ServicesState()
}
