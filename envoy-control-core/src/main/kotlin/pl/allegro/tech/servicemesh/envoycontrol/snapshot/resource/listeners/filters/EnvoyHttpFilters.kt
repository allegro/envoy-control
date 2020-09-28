package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters

import io.envoyproxy.envoy.api.v2.core.Metadata
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.HttpFilterFactory

class EnvoyHttpFilters(
    val ingressFilters: List<HttpFilterFactory>,
    val egressFilters: List<HttpFilterFactory>,
    val ingressMetadata: Metadata?
) {
    companion object {
        val emptyFilters = EnvoyHttpFilters(listOf(), listOf(), null)

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
