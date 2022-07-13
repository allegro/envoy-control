package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters

import com.google.protobuf.Any
import com.google.protobuf.ListValue
import com.google.protobuf.Struct
import com.google.protobuf.Value
import io.envoyproxy.envoy.config.core.v3.Metadata
import io.envoyproxy.envoy.extensions.filters.http.lua.v3.Lua
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.IncomingPermissionsProperties

class LuaFilterFactory(private val incomingPermissionsProperties: IncomingPermissionsProperties) {

    private val ingressRbacLoggingScript: String = this::class.java.classLoader
        .getResource("lua/ingress_rbac_logging.lua")!!.readText()

    private val ingressRbacLoggingFilter: HttpFilter? = if (incomingPermissionsProperties.enabled) {
        HttpFilter.newBuilder()
            .setName("envoy.lua")
            .setTypedConfig(Any.pack(Lua.newBuilder().setInlineCode(ingressRbacLoggingScript).build()))
            .build()
    } else {
        null
    }

    private val trustedClientIdentityHeader = incomingPermissionsProperties.trustedClientIdentityHeader

    fun ingressRbacLoggingFilter(group: Group): HttpFilter? =
        ingressRbacLoggingFilter.takeIf { group.proxySettings.incoming.permissionsEnabled }

    private val ingressClientNameHeaderScript: String = this::class.java.classLoader
        .getResource("lua/ingress_client_name_header.lua")!!.readText()

    private val ingressClientNameHeaderFilter: HttpFilter =
        HttpFilter.newBuilder()
            .setName("ingress.client.lua")
            .setTypedConfig(Any.pack(Lua.newBuilder().setInlineCode(ingressClientNameHeaderScript).build()))
            .build()

    private val sanUriWildcardRegexForLua = SanUriMatcherFactory(incomingPermissionsProperties.tlsAuthentication)
        .sanUriWildcardRegexForLua

    fun ingressScriptsMetadata(group: Group, flags: Map<String, Boolean> = mapOf()): Metadata {
        return Metadata.newBuilder()
            .putFilterMetadata("envoy.filters.http.lua",
                Struct.newBuilder()
                    .putFields("client_identity_headers",
                        Value.newBuilder()
                            .setListValue(ListValue.newBuilder()
                                .addAllValues(
                                    incomingPermissionsProperties.clientIdentityHeaders
                                        .map { Value.newBuilder().setStringValue(it).build() }
                                )
                                .build()
                            ).build()
                    )
                    .putFields("request_id_headers",
                        Value.newBuilder()
                            .setListValue(ListValue.newBuilder()
                                .addAllValues(
                                    incomingPermissionsProperties.requestIdentificationHeaders
                                        .map { Value.newBuilder().setStringValue(it).build() }
                                )
                                .build()
                            ).build()
                    )
                    .putFields(
                        "trusted_client_identity_header",
                        Value.newBuilder()
                            .setStringValue(trustedClientIdentityHeader)
                            .build()
                    )
                    .putFields(
                        "san_uri_lua_pattern",
                        Value.newBuilder()
                            .setStringValue(sanUriWildcardRegexForLua).build()
                    ).putFields("clients_allowed_to_all_endpoints",
                        Value.newBuilder()
                            .setListValue(ListValue.newBuilder()
                                .addAllValues(
                                    incomingPermissionsProperties.clientsAllowedToAllEndpoints
                                        .map { Value.newBuilder().setStringValue(it).build() }
                                )
                                .build()
                            ).build()
                    )
                    .putFields(
                        "service_name",
                        Value.newBuilder()
                            .setStringValue(group.serviceName)
                            .build()
                    )
                    .putFields(
                        "discovery_service_name",
                        Value.newBuilder()
                            .setStringValue(group.discoveryServiceName ?: "")
                            .build()
                    ).putFields("flags", Value.newBuilder()
                        .setStructValue(Struct.newBuilder()
                            .apply {
                                flags.forEach {
                                    putFields(
                                        it.key,
                                        Value.newBuilder().setBoolValue(it.value).build()
                                    )
                                }
                            }
                            .build()
                        ).build()
                    )
                    .build()
            ).build()
    }

    fun ingressClientNameHeaderFilter(): HttpFilter? =
        ingressClientNameHeaderFilter.takeIf { trustedClientIdentityHeader.isNotEmpty() }
}
