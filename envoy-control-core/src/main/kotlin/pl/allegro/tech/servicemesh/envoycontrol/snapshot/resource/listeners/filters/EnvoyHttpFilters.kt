package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters

import io.envoyproxy.envoy.config.core.v3.Metadata
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.HttpFilterFactory
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.routes.IngressMetadataFactory

class EnvoyHttpFilters(
    val ingressFilters: List<HttpFilterFactory>,
    val egressFilters: List<HttpFilterFactory>,
    val ingressMetadata: IngressMetadataFactory = { _ -> Metadata.getDefaultInstance() }
) {
    companion object {
        val emptyFilters = EnvoyHttpFilters(listOf(), listOf()) { Metadata.getDefaultInstance() }

        fun defaultFilters(
            snapshotProperties: SnapshotProperties,
            flags: Map<String, Boolean> = mapOf()
        ): EnvoyHttpFilters {
            val defaultFilters = EnvoyDefaultFilters(snapshotProperties, flags)
            return EnvoyHttpFilters(
                defaultFilters.ingressFilters(),
                defaultFilters.defaultEgressFilters,
                defaultFilters.defaultIngressMetadata
            )
        }
    }
}
