@file:Suppress("MagicNumber")

package pl.allegro.tech.servicemesh.envoycontrol

import pl.allegro.tech.servicemesh.envoycontrol.server.ServerProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotProperties
import pl.allegro.tech.servicemesh.envoycontrol.synchronization.SyncProperties

class EnvoyControlProperties {
    var server = ServerProperties()
    var envoy = EnvoyProperties()
    var sync = SyncProperties()
    var serviceFilters = ServiceFilters()
    var tracing = TracingProperties()
}

class TracingProperties {
    var services: List<String> = emptyList()
}

class EnvoyProperties {
    var snapshot = SnapshotProperties()
}

class ServiceFilters {
    var excludedNamesPatterns: List<Regex> = emptyList()
}
