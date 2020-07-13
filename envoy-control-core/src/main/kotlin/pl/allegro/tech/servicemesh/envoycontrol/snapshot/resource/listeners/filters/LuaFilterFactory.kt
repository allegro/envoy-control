package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters

import com.google.protobuf.Any
import io.envoyproxy.envoy.config.filter.http.lua.v2.Lua
import io.envoyproxy.envoy.config.filter.network.http_connection_manager.v2.HttpFilter
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.IncomingPermissionsProperties

class LuaFilterFactory(incomingPermissionsProperties: IncomingPermissionsProperties) {

    private val ingressScript: String = this::class.java.classLoader
        .getResource("lua/ingress_handler.lua")!!.readText()

    private val ingressHttpFilter: HttpFilter? = if (incomingPermissionsProperties.enabled) {
        HttpFilter.newBuilder()
            .setName("envoy.lua")
            .setTypedConfig(Any.pack(Lua.newBuilder().setInlineCode(ingressScript).build()))
            .build()
    } else {
        null
    }

    fun ingressFilter(group: Group): HttpFilter? =
        ingressHttpFilter.takeIf { group.proxySettings.incoming.permissionsEnabled }
}
