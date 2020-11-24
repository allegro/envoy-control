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

    private val ingressScriptsMetadata = Metadata.newBuilder()
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
                .putFields("trusted_client_identity_header",  // TODO(mf): use in rbac logging script
                    Value.newBuilder()
                        .setStringValue(incomingPermissionsProperties.trustedClientIdentityHeader)
                        .build()
                )
                .putFields("san_uri_client_name_regex",
                    Value.newBuilder()
                        .setStringValue(
                            incomingPermissionsProperties.tlsAuthentication.sanUriClientNameRegex
                        ).build()
                )
                .build()
        ).build()

    fun ingressRbacLoggingFilter(group: Group): HttpFilter? =
        ingressRbacLoggingFilter.takeIf { group.proxySettings.incoming.permissionsEnabled }

    private val ingressClientScript: String = this::class.java.classLoader
            .getResource("lua/ingress_client.lua")!!.readText()

    private val ingressClientFilter: HttpFilter =
        HttpFilter.newBuilder()
                .setName("ingress.client.lua")
                .setTypedConfig(Any.pack(Lua.newBuilder().setInlineCode(ingressClientScript).build()))
                .build()

    fun ingressClientFilter(): HttpFilter = ingressClientFilter

    fun ingressScriptsMetadata(): Metadata = ingressScriptsMetadata
}
