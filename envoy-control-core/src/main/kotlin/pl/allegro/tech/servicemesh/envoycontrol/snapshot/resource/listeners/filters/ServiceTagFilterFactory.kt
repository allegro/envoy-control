package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters

import io.envoyproxy.envoy.extensions.filters.http.header_to_metadata.v3.Config
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.ServiceTagsProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.HttpFilterFactory
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters.LuaFilterFactory.Companion.createLuaFilter

class ServiceTagFilterFactory(private val properties: ServiceTagsProperties) {

    companion object {
        const val AUTO_SERVICE_TAG_PREFERENCE_METADATA = "auto_service_tag_preference"
    }

    fun headerToMetadataFilterRules(): List<Config.Rule> {
        return listOf(
            Config.Rule.newBuilder()
                .setHeader(properties.header)
                .setRemove(false)
                .setOnHeaderPresent(
                    Config.KeyValuePair.newBuilder()
                        .setKey(properties.metadataKey)
                        .setMetadataNamespace("envoy.lb")
                        .setType(Config.ValueType.STRING)
                        .build()
                )
                .build()
        )
    }

    fun egressFilters(): Array<HttpFilterFactory> = arrayOf(
        { group: Group, _ -> luaEgressServiceTagPreferenceFilter(group) },
        { _, _ -> luaEgressAutoServiceTagsFilter }
    )

    private fun luaEgressServiceTagPreferenceFilter(group: Group): HttpFilter? =
        if (properties.preferenceRouting.isEnabledFor(group.serviceName)) {
            luaEgressServiceTagPreferenceFilter
        } else {
            null
        }

    private val luaEgressAutoServiceTagsFilter: HttpFilter? =
        if (properties.shouldRejectRequestsWithDuplicatedAutoServiceTag()) {
            createLuaFilter(
                luaFile = "lua/egress_auto_service_tags.lua",
                filterName = "envoy.lua.auto_service_tags",
                variables = mapOf(
                    "SERVICE_TAG_METADATA_KEY" to properties.metadataKey,
                )
            )
        } else {
            null
        }

    private val luaEgressServiceTagPreferenceFilter: HttpFilter = createLuaFilter(
        luaFile = "lua/egress_service_tag_preference.lua",
        filterName = "envoy.lua.service_tag_preference",
        variables = mapOf(
            "SERVICE_TAG_METADATA_KEY" to properties.metadataKey,
            "SERVICE_TAG_HEADER" to properties.header,
            "SERVICE_TAG_PREFERENCE_HEADER" to properties.preferenceRouting.header,
            "DEFAULT_SERVICE_TAG_PREFERENCE_ENV" to properties.preferenceRouting.defaultPreferenceEnv,
            "DEFAULT_SERVICE_TAG_PREFERENCE_FALLBACK" to properties.preferenceRouting.defaultPreferenceFallback,
            "FALLBACK_TO_ANY_IF_DEFAULT_PREFERENCE_EQUAL_TO" to
                (properties.preferenceRouting.fallbackToAny.enableForServicesWithDefaultPreferenceEqualTo ?: "")
        )
    )
}
