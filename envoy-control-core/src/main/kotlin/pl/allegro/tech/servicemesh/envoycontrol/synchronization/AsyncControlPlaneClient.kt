package pl.allegro.tech.servicemesh.envoycontrol.synchronization

import pl.allegro.tech.servicemesh.envoycontrol.services.ServicesState
import reactor.core.publisher.Mono
import java.net.URI

interface AsyncControlPlaneClient {
    fun getState(uri: URI): Mono<ServicesState>
}
