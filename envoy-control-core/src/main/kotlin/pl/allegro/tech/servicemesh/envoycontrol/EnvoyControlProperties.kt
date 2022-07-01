@file:Suppress("MagicNumber")

package pl.allegro.tech.servicemesh.envoycontrol

import pl.allegro.tech.servicemesh.envoycontrol.server.ServerProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotProperties
import pl.allegro.tech.servicemesh.envoycontrol.synchronization.SyncProperties
import java.util.UUID

class EnvoyControlProperties {
    var server = ServerProperties()
    var envoy = EnvoyProperties()
    var sync = SyncProperties()
    var serviceFilters = ServiceFilters()
}

class EnvoyProperties {
    var snapshot = SnapshotProperties()
    var controlPlaneIdentifier = UUID.randomUUID().toString()
}

class ServiceFilters {
    var excludedNamesPatterns: List<Regex> = emptyList()
}
