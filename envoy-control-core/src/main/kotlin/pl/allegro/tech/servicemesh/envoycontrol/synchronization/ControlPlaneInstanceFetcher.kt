package pl.allegro.tech.servicemesh.envoycontrol.synchronization

import java.net.URI

interface ControlPlaneInstanceFetcher {
    fun instances(zone: String): List<URI>
}
