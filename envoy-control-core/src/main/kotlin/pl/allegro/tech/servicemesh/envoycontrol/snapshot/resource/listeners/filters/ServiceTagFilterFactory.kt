package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters

import com.google.protobuf.Any
import io.envoyproxy.envoy.extensions.filters.http.header_to_metadata.v3.Config
import io.envoyproxy.envoy.extensions.filters.http.lua.v3.Lua
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.ServiceTagsProperties

class ServiceTagFilterFactory(
    private val properties: ServiceTagsProperties,
    localReplyLuaScript: String
) {

    companion object {
        const val AUTO_SERVICE_TAG_PREFERENCE_METADATA = "auto_service_tag_preference"
        const val SERVICE_TAG_METADATA_KEY_METADATA = "service_tag_metadata_key"
    }

    private val luaEgressScript: String = this::class.java.classLoader
        .getResource("lua/egress_service_tags.lua")!!.readText() + localReplyLuaScript

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

    fun luaEgressFilter(): HttpFilter = HttpFilter.newBuilder()
        .setName("envoy.lua.servicetags")
        .setTypedConfig(
            Any.pack(
                Lua.newBuilder()
                    .setInlineCode(luaEgressScript)
                    .build()
            )
        ).build()
}
