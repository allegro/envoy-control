package pl.allegro.tech.servicemesh.envoycontrol.snapshot.listeners.filters

import com.google.protobuf.Any
import io.envoyproxy.envoy.config.filter.http.lua.v2.Lua
import io.envoyproxy.envoy.config.filter.network.http_connection_manager.v2.HttpFilter

class EnvoyShadowRulesFiltersFactory {
    companion object {
        // TODO(mfalkowski): faktyczny skrypt lua nie powinien być przesyłany inline. Inline powinnien być tylko mały
        //   skrypt startujący, który wczytuje właściwy skrypt. Właściwy skrypt powinien siedzieć razem z envoyem.
        //   Sprawdz jak mamy w AEC zrobiony skrypt do service tagów:
        //      alle-envoy-control/src/main/resources/filters/script.lua
        //      alle-envoy-control/src/test/resources/envoy/extra/lua
        //      alle-envoy-control/src/main/kotlin/pl/allegro/tech/servicemesh/envoycontrol/alle/intrastructure/filters/AlleEnvoyHttpFiltersFactory.kt
        //   Do metadanych envoya (a w konsekwencji do ListenersConfig) trzeba wprowadzić nową zmienną: 'enable_lua_ingress_script`, która będzie ustawiana na true
        //      w nowych wersjach envoy-wrapper, które posiadają już ten skrypt Lua. Tylko wtedy można dodawać ten luaFilter.
        private val luaScriptContents = this::class.java.classLoader
            .getResource("filters/handler.lua")!!.readText()

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
}
