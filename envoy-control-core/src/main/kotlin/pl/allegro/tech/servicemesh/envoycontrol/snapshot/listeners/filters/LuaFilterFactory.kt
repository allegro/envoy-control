package pl.allegro.tech.servicemesh.envoycontrol.snapshot.listeners.filters

import com.google.protobuf.Any
import io.envoyproxy.envoy.config.filter.http.lua.v2.Lua
import io.envoyproxy.envoy.config.filter.network.http_connection_manager.v2.HttpFilter
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.IncomingPermissionsProperties

class LuaFilterFactory(incomingPermissionsProperties: IncomingPermissionsProperties) {

    private val ingressHttpFilter = if (incomingPermissionsProperties.enabled) {
        HttpFilter.newBuilder()
            .setName("envoy.lua")
            .setTypedConfig(Any.pack(Lua.newBuilder().setInlineCode(getIngressScript()).build()))
            .build()
    } else {
        null
    }

    private fun getIngressScript(): String {
        // TODO(mfalkowski): move lua file to envoy-control-core
        return this::class.java.classLoader
                .getResource("filters/ingress_handler.lua")!!.readText()
                .replace("return M",
                this::class.java.classLoader
                    .getResource("filters/script_ingress_handler.lua")!!.readText()
        )
    }

    fun ingressFilter(group: Group): HttpFilter? =
        ingressHttpFilter.takeIf { group.proxySettings.incoming.permissionsEnabled }
}
