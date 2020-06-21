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
            val addFilterRBACmessage: Boolean = group.proxySettings.incoming.unlistedEndpointsPolicy?.equals(Incoming.UnlistedEndpointsPolicy.LOG) ?: false ||
                    group.proxySettings.incoming.endpoints.stream().anyMatch {
                it.unlistedClientsPolicy?.equals(IncomingEndpoint.UnlistedClientsPolicy.LOG) ?: false
            }

            if (addFilterRBACmessage) {
                return HttpFilter.newBuilder()
                        .setName("envoy.lua")
                        .setTypedConfig(
                                Any.pack(
                                        Lua.newBuilder()
                                                .setInlineCode(luaScriptContents)
                                                .build()
                                )
                        )
                        .build()
            }
            return null
        }
    }
}
