package pl.allegro.tech.servicemesh.envoycontrol.snapshot.listeners.filters

import com.google.protobuf.Any
import io.envoyproxy.envoy.config.filter.http.lua.v2.Lua
import io.envoyproxy.envoy.config.filter.network.http_connection_manager.v2.HttpFilter

class LuaFilterFactory {
    private fun getScripts():String {
        val ingress = this::class.java.classLoader
                .getResource("filters/ingress_handler.lua")!!.readText()
        val script = this::class.java.classLoader
                .getResource("filters/script_ingress_handler.lua")!!.readText()
        val result = ingress.replace("return M", script)
        return result
    }

    fun ingressFilter(enabled: Boolean): HttpFilter? {
        if (enabled) {
            return HttpFilter.newBuilder()
                    .setName("envoy.lua")
                    .setTypedConfig(Any.pack(Lua.newBuilder().setInlineCode(getScripts()).build()))
                    .build()
        }
        return null
    }
}
