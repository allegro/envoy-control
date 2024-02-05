package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters

import io.envoyproxy.envoy.config.core.v3.Metadata
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.HttpFilterFactory
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.routes.IngressMetadataFactory

class EnvoyHttpFilters(
    val ingressFilters: List<HttpFilterFactory>,
    val egressFilters: List<HttpFilterFactory>,
    val ingressMetadata: IngressMetadataFactory = { _: Group, _: String -> Metadata.getDefaultInstance() }
) {
    companion object {
        val emptyFilters = EnvoyHttpFilters(listOf(), listOf()) { _, _ -> Metadata.getDefaultInstance() }

        fun defaultFilters(
            snapshotProperties: SnapshotProperties,
            customLuaMetadata: LuaMetadataProperty.StructPropertyLua = LuaMetadataProperty.StructPropertyLua()
        ): EnvoyHttpFilters {
            val defaultFilters = EnvoyDefaultFilters(snapshotProperties, customLuaMetadata)
            return EnvoyHttpFilters(
                defaultFilters.ingressFilters(),
                defaultFilters.defaultEgressFilters,
                defaultFilters.defaultIngressMetadata
            )
        }
    }
}
