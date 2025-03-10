package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters

import com.google.protobuf.Any
import io.envoyproxy.envoy.config.core.v3.DataSource
import io.envoyproxy.envoy.extensions.filters.http.header_to_metadata.v3.Config
import io.envoyproxy.envoy.extensions.filters.http.lua.v3.Lua
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.ServiceTagsProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.HttpFilterFactory

class ServiceTagFilterFactory(private val properties: ServiceTagsProperties) {

    companion object {
        // TODO: [service-tag]: remove
        const val AUTO_SERVICE_TAG_PREFERENCE_METADATA = "auto_service_tag_preference"
        const val SERVICE_TAG_METADATA_KEY_METADATA = "service_tag_metadata_key"
        const val REJECT_REQUEST_SERVICE_TAG_DUPLICATE = "reject_request_service_tag_duplicate"

        private val placeholderFormat = "%([0-9a-z_]+)%".toRegex(RegexOption.IGNORE_CASE)
        private val replacementFormat = "[a-z0-9_-]+".toRegex(RegexOption.IGNORE_CASE)
    }

    fun headerToMetadataFilterRule(group: Group): Config.Rule? {
        if (properties.preferenceRouting.isEnabledFor(group.serviceName)) {
            return null
        }
        return headerToMetadataFilterRulePreferenceDisabled
    }

    fun egressFilters(): Array<HttpFilterFactory> = arrayOf(
        { group: Group, _ -> luaEgressServiceTagPreferenceFilter(group) },
        { _, _ -> luaEgressAutoServiceTagsFilter }
    )

    private fun luaEgressServiceTagPreferenceFilter(group: Group): HttpFilter? =
        when (properties.preferenceRouting.isEnabledFor(group.serviceName)) {
            true -> luaEgressServiceTagPreferenceFilter
            false -> null
        }

    val luaEgressAutoServiceTagsFilter: HttpFilter? = properties.isAutoServiceTagEffectivelyEnabled()
        .takeIf { it }
        ?.let {
            createLuaFilter(
                luaFile = "lua/egress_auto_service_tags.lua",
                filterName = "envoy.lua.auto_service_tags"
            )
        }

    private val headerToMetadataFilterRulePreferenceDisabled = Config.Rule.newBuilder()
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

    private val luaEgressServiceTagPreferenceFilter = createLuaFilter(
        luaFile = "lua/egress_service_tag_preference.lua",
        filterName = "envoy.lua.service_tag_preference",
        variables = mapOf(
            "SERVICE_TAG_METADATA_KEY" to properties.metadataKey,
            "SERVICE_TAG_HEADER" to properties.header,
            "SERVICE_TAG_PREFERENCE_HEADER" to properties.preferenceRouting.header,
            "DEFAULT_SERVICE_TAG_PREFERENCE_ENV" to properties.preferenceRouting.defaultPreferenceEnv,
            "DEFAULT_SERVICE_TAG_PREFERENCE_FALLBACK" to properties.preferenceRouting.defaultPreferenceFallback,
        )
    )

    private fun createLuaFilter(
        luaFile: String,
        filterName: String,
        variables: Map<String, String> = emptyMap()
    ): HttpFilter {
        val scriptTemplate = this::class.java.classLoader.getResource(luaFile)!!.readText()

        val script = scriptTemplate.replace(placeholderFormat) { match ->
            val key = match.groupValues[1]
            val replacement = variables[key]
                ?: throw IllegalArgumentException("Missing replacement for placeholder: $key")
            require(replacement.matches(replacementFormat)) { "invalid replacement format: $replacement" }
            replacement
        }

        return HttpFilter.newBuilder()
            .setName(filterName)
            .setTypedConfig(
                Any.pack(
                    Lua.newBuilder().setDefaultSourceCode(
                        DataSource.newBuilder()
                            .setInlineString(script)
                    ).build()
                )
            ).build()
    }
}
