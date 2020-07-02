package pl.allegro.tech.servicemesh.envoycontrol.snapshot.listeners.filters

import com.google.protobuf.Any
import io.envoyproxy.envoy.config.filter.http.lua.v2.Lua
import io.envoyproxy.envoy.config.filter.network.http_connection_manager.v2.HttpFilter

class LuaIngressFilterFactory {
    private val luaScriptContents = this::class.java.classLoader
            .getResource("filters/handler.lua")!!.readText()

    // TODO(mfalkowski): powinniśmy stworzyć unit testy dla skryptu lua, tak jak mamy we wrapperze
    fun luaFilter(enabled: Boolean): HttpFilter? {
        if (enabled) {
            return HttpFilter.newBuilder()
                    .setName("envoy.lua")
                    .setTypedConfig(Any.pack(Lua.newBuilder().setInlineCode(luaScriptContents).build()))
                    .build()
        }
        return null
    }
}
