package pl.allegro.tech.servicemesh.envoycontrol.synchronization

import pl.allegro.tech.servicemesh.envoycontrol.services.ServicesState
import java.net.URI

interface ControlPlaneClient {
    fun getState(uri: URI): ServicesState
}
