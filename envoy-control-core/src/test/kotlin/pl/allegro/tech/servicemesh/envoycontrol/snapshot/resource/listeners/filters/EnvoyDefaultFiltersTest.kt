package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters

import com.google.protobuf.Any
import io.envoyproxy.envoy.extensions.filters.http.lua.v3.Lua
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.GlobalSnapshot
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotProperties

class EnvoyDefaultFiltersTest {

    private val defaultFilters = EnvoyDefaultFilters(SnapshotProperties(), emptyMap())

    @Test
    fun `should create default filters`() {
        // given
        val expectedFilters = arrayOf(
            defaultFilters.defaultClientNameHeaderFilter,
            defaultFilters.defaultAuthorizationHeaderFilter,
            defaultFilters.defaultJwtHttpFilter,
            defaultFilters.defaultRbacLoggingFilter,
            defaultFilters.defaultRbacFilter,
            defaultFilters.defaultRateLimitLuaFilter,
            defaultFilters.defaultRateLimitFilter,
            defaultFilters.defaultEnvoyRouterHttpFilter
        )

        // when
        val filters = defaultFilters.ingressFilters()

        // then
        assertThat(filters).containsExactly(*expectedFilters)
    }

    @Test
    fun `should create filters with custom filter in between`() {
        // given
        val customFilter = { _: Group, _: GlobalSnapshot ->
            HttpFilter.newBuilder()
                .setName("custom.ingress.client.lua")
                .setTypedConfig(Any.pack(Lua.newBuilder().setInlineCode("").build()))
                .build()
        }
        val expectedFilters = arrayOf(
            defaultFilters.defaultClientNameHeaderFilter,
            defaultFilters.defaultAuthorizationHeaderFilter,
            defaultFilters.defaultJwtHttpFilter,
            customFilter,
            defaultFilters.defaultRbacLoggingFilter,
            defaultFilters.defaultRbacFilter,
            defaultFilters.defaultRateLimitLuaFilter,
            defaultFilters.defaultRateLimitFilter,
            defaultFilters.defaultEnvoyRouterHttpFilter
        )

        // when
        val filters = defaultFilters.ingressFilters(customFilter)

        // then
        assertThat(filters).containsExactly(*expectedFilters)
    }
}
