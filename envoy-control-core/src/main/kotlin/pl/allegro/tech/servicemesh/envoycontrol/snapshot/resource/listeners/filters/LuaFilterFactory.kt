package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters

import com.google.protobuf.Any
import com.google.protobuf.ListValue
import com.google.protobuf.Struct
import com.google.protobuf.Value
import io.envoyproxy.envoy.config.core.v3.Metadata
import io.envoyproxy.envoy.extensions.filters.http.lua.v3.Lua
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters.LuaMetadataProperty.ListPropertyLua
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters.LuaMetadataProperty.StringPropertyLua
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters.LuaMetadataProperty.StructPropertyLua

class LuaFilterFactory(private val snapshotProperties: SnapshotProperties) {

    private val ingressRbacLoggingScript: String = this::class.java.classLoader
        .getResource("lua/ingress_rbac_logging.lua")!!.readText()

    private val ingressRbacLoggingFilter: HttpFilter? = if (snapshotProperties.incomingPermissions.enabled) {
        HttpFilter.newBuilder()
            .setName("envoy.lua")
            .setTypedConfig(Any.pack(Lua.newBuilder().setInlineCode(ingressRbacLoggingScript).build()))
            .build()
    } else {
        null
    }

    private val trustedClientIdentityHeader = snapshotProperties.incomingPermissions.trustedClientIdentityHeader

    fun ingressRbacLoggingFilter(group: Group): HttpFilter? =
        ingressRbacLoggingFilter.takeIf { group.proxySettings.incoming.permissionsEnabled }

    private val ingressClientNameHeaderScript: String = this::class.java.classLoader
        .getResource("lua/ingress_client_name_header.lua")!!.readText()

    private val ingressClientNameHeaderFilter: HttpFilter =
        HttpFilter.newBuilder()
            .setName("ingress.client.lua")
            .setTypedConfig(Any.pack(Lua.newBuilder().setInlineCode(ingressClientNameHeaderScript).build()))
            .build()

    private val ingressCurrentZoneHeaderScript: String = this::class.java.classLoader
        .getResource("lua/ingress_current_zone_header.lua")!!.readText()

    private val ingressCurrentZoneHeaderFilter: HttpFilter =
        HttpFilter.newBuilder()
            .setName("ingress.zone.lua")
            .setTypedConfig(Any.pack(Lua.newBuilder().setInlineCode(ingressCurrentZoneHeaderScript).build()))
            .build()

    private val sanUriWildcardRegexForLua = SanUriMatcherFactory(
        snapshotProperties.incomingPermissions.tlsAuthentication
    )
        .sanUriWildcardRegexForLua

    fun ingressScriptsMetadata(
        group: Group,
        customLuaMetadata: StructPropertyLua = StructPropertyLua(),
        currentZone: String
    ): Metadata {
        val metadata = StructPropertyLua(
            "client_identity_headers" to ListPropertyLua(
                snapshotProperties.incomingPermissions
                    .clientIdentityHeaders.map(::StringPropertyLua)
            ),
            "request_id_headers" to ListPropertyLua(
                snapshotProperties.incomingPermissions.requestIdentificationHeaders.map(
                    ::StringPropertyLua
                )
            ),
            "trusted_client_identity_header" to StringPropertyLua(trustedClientIdentityHeader),
            "san_uri_lua_pattern" to StringPropertyLua(sanUriWildcardRegexForLua),
            "clients_allowed_to_all_endpoints" to ListPropertyLua(
                snapshotProperties.incomingPermissions.clientsAllowedToAllEndpoints.map(
                    ::StringPropertyLua
                )
            ),
            "service_name" to StringPropertyLua(group.serviceName),
            "service_id" to StringPropertyLua(group.serviceId ?: ""),
            "discovery_service_name" to StringPropertyLua(group.discoveryServiceName ?: ""),
            "rbac_headers_to_log" to ListPropertyLua(
                snapshotProperties.incomingPermissions.headersToLogInRbac.map(::StringPropertyLua)
            ),
            "traffic_splitting_zone_header_name" to StringPropertyLua(
                snapshotProperties.loadBalancing.trafficSplitting.headerName
            ),
            "current_zone" to StringPropertyLua(currentZone)
        ) + customLuaMetadata
        return Metadata.newBuilder()
            .putFilterMetadata("envoy.filters.http.lua", metadata.toValue().structValue)
            .build()
    }

    fun ingressClientNameHeaderFilter(): HttpFilter? =
        ingressClientNameHeaderFilter.takeIf { trustedClientIdentityHeader.isNotEmpty() }

    fun ingressCurrentZoneHeaderFilter(): HttpFilter = ingressCurrentZoneHeaderFilter
}

sealed class LuaMetadataProperty<T>(open val value: T) {
    abstract fun toValue(): Value

    data class BooleanPropertyLua private constructor(override val value: Boolean) :
        LuaMetadataProperty<Boolean>(value) {
        companion object {
            val TRUE = BooleanPropertyLua(true)
            val FALSE = BooleanPropertyLua(false)

            fun of(value: Boolean): BooleanPropertyLua {
                return if (value) TRUE else FALSE
            }
        }

        override fun toValue(): Value {
            return Value.newBuilder()
                .setBoolValue(value)
                .build()
        }
    }

    data class NumberPropertyLua(override val value: Double) : LuaMetadataProperty<Double>(value) {
        override fun toValue(): Value {
            return Value.newBuilder()
                .setNumberValue(value)
                .build()
        }
    }

    data class StringPropertyLua(override val value: String) : LuaMetadataProperty<String>(value) {
        override fun toValue(): Value {
            return Value.newBuilder()
                .setStringValue(value)
                .build()
        }
    }

    data class ListPropertyLua<T>(override val value: List<LuaMetadataProperty<T>>) :
        LuaMetadataProperty<List<LuaMetadataProperty<T>>>(value),
        List<LuaMetadataProperty<T>> by value {

        constructor(vararg pairs: LuaMetadataProperty<T>) : this(pairs.toList())

        override fun toValue(): Value {
            return Value.newBuilder()
                .setListValue(ListValue.newBuilder().addAllValues(value.map { it.toValue() }))
                .build()
        }
    }

    data class StructPropertyLua(override val value: Map<String, LuaMetadataProperty<out kotlin.Any>> = mapOf()) :
        LuaMetadataProperty<Map<String, LuaMetadataProperty<out kotlin.Any>>>(value),
        Map<String, LuaMetadataProperty<out kotlin.Any>> by value {

        constructor(vararg pairs: Pair<String, LuaMetadataProperty<out kotlin.Any>>) : this(pairs.toMap())

        override fun toValue(): Value {
            return Value.newBuilder()
                .setStructValue(Struct.newBuilder()
                    .apply {
                        value.forEach {
                            putFields(
                                it.key,
                                it.value.toValue()
                            )
                        }
                    }
                    .build()
                ).build()
        }

        operator fun plus(other: StructPropertyLua) = StructPropertyLua(this.value + other.value)
    }
}
