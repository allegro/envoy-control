package pl.allegro.tech.servicemesh.envoycontrol.snapshot.listeners.filters

import com.google.protobuf.Any
import io.envoyproxy.envoy.config.filter.http.lua.v2.Lua
import io.envoyproxy.envoy.config.filter.network.http_connection_manager.v2.HttpFilter
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group
import pl.allegro.tech.servicemesh.envoycontrol.groups.Incoming
import pl.allegro.tech.servicemesh.envoycontrol.groups.IncomingEndpoint

class EnvoyShadowRulesFiltersFactory {
    companion object {
        private val luaScriptContents = this::class.java.classLoader
            .getResource("filters/handler.lua")!!.readText()

        fun luaFilter(group: Group): HttpFilter? {
            if (addFilterRBACLuaFilter(group.proxySettings.incoming)) {
                return HttpFilter.newBuilder()
                        .setName("envoy.lua")
                        .setTypedConfig(Any.pack(Lua.newBuilder().setInlineCode(luaScriptContents).build()))
                        .build()
            }
            return null
        }

        private fun addFilterRBACLuaFilter(incoming: Incoming): Boolean {
            return incoming.unlistedEndpointsPolicy == Incoming.UnlistedEndpointsPolicy.LOG ||
                    incoming.endpoints.stream().anyMatch {
                        it.unlistedClientsPolicy == IncomingEndpoint.UnlistedClientsPolicy.LOG
                    }
        }
    }
}
