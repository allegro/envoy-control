package pl.allegro.tech.servicemesh.envoycontrol.synchronization

import pl.allegro.tech.servicemesh.envoycontrol.services.ServicesState
import java.net.URI
import java.util.concurrent.CompletableFuture

interface ControlPlaneClient {
    fun getState(uri: URI): CompletableFuture<ServicesState>
}
