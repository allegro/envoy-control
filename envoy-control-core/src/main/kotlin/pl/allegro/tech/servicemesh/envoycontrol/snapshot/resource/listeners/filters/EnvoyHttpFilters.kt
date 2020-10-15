package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters

import io.envoyproxy.envoy.config.core.v3.Metadata
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.HttpFilterFactory

class EnvoyHttpFilters(
    val ingressFilters: List<HttpFilterFactory>,
    val egressFilters: List<HttpFilterFactory>,
    val ingressMetadata: Metadata = Metadata.getDefaultInstance()
) {
    companion object {
        val emptyFilters = EnvoyHttpFilters(listOf(), listOf(), Metadata.getDefaultInstance())

        fun defaultFilters(snapshotProperties: SnapshotProperties): EnvoyHttpFilters {
            val defaultFilters = EnvoyDefaultFilters(snapshotProperties)
            return EnvoyHttpFilters(
                defaultFilters.defaultIngressFilters,
                defaultFilters.defaultEgressFilters,
                defaultFilters.defaultIngressMetadata
            )
        }
    }
}
