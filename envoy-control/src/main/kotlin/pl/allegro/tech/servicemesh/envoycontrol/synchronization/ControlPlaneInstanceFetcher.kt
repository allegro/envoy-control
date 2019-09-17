package pl.allegro.tech.servicemesh.envoycontrol.synchronization

import java.net.URI

interface ControlPlaneInstanceFetcher {
    fun instances(dc: String): List<URI>
}
