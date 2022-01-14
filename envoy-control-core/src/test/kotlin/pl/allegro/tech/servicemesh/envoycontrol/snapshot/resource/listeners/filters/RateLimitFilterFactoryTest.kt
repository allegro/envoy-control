package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters

import io.envoyproxy.envoy.extensions.filters.http.lua.v3.Lua
import io.envoyproxy.envoy.extensions.filters.http.ratelimit.v3.RateLimit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.groups.CommunicationMode
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group
import pl.allegro.tech.servicemesh.envoycontrol.groups.Incoming
import pl.allegro.tech.servicemesh.envoycontrol.groups.IncomingRateLimitEndpoint
import pl.allegro.tech.servicemesh.envoycontrol.groups.ProxySettings
import pl.allegro.tech.servicemesh.envoycontrol.groups.ServicesGroup
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.RateLimitProperties

class RateLimitFilterFactoryTest {
    private val properties = RateLimitProperties()
    private val rateLimitFilterFactory = RateLimitFilterFactory(properties)

    @Test
    fun `should not create ratelimit filter if no rate limits exist`() {
        // given
        val group = createGroupWithIncomingRateLimits()

        // when
        val filter = rateLimitFilterFactory.createGlobalRateLimitFilter(group)

        // then
        assertThat(filter).isNull()
    }

    @Test
    fun `should create ratelimit filter with proper domain and ratelimit service name`() {
        // given
        val group = createGroupWithIncomingRateLimits(
            IncomingRateLimitEndpoint("/hello", rateLimit = "0/s")
        )
        properties.domain = "rl_domain"
        properties.serviceName = "rl_service"

        // when
        val filter = rateLimitFilterFactory.createGlobalRateLimitFilter(group)

        // then
        assertThat(filter!!.name).isEqualTo("envoy.filters.http.ratelimit")
        assertThat(filter.typedConfig).isInstanceOf(com.google.protobuf.Any::class.java)
        val config = filter.typedConfig as com.google.protobuf.Any
        assertThat(config.`is`(RateLimit::class.java)).isTrue()
        val rateLimit = config.unpack(RateLimit::class.java)
        assertThat(rateLimit.domain).isEqualTo("rl_domain")
        assertThat(rateLimit.rateLimitService.grpcService.envoyGrpc.clusterName).isEqualTo("rl_service")
    }

    @Test
    fun `should not create Lua filter if no rate limits exist`() {
        // given
        val group = createGroupWithIncomingRateLimits()

        // when
        val filter = rateLimitFilterFactory.createLuaLimitOverrideFilter(group)

        // then
        assertThat(filter).isNull()
    }

    @Test
    fun `should create Lua filter with correct code`() {
        // given
        val group = createGroupWithIncomingRateLimits(
                IncomingRateLimitEndpoint("/hello", rateLimit = "13/s"),
                IncomingRateLimitEndpoint("/banned", rateLimit = "178/m")
        )

        // when
        val filter = rateLimitFilterFactory.createLuaLimitOverrideFilter(group)

        // then
        assertThat(filter!!.name).isEqualTo("envoy.lua.ratelimit")
        assertThat(filter.typedConfig).isInstanceOf(com.google.protobuf.Any::class.java)
        val config = filter.typedConfig as com.google.protobuf.Any
        assertThat(config.`is`(Lua::class.java)).isTrue()
        val lua = config.unpack(Lua::class.java)
        assertThat(lua.inlineCode.smartTrimmed()).containsExactly(*"""
            function envoy_on_request(request_handle)
                request_handle:streamInfo():dynamicMetadata():set("envoy.filters.http.ratelimit.override", "_ecb42b24b3c81841bfdd244fedf430f8", {
                    unit = "SECOND",
                    requests_per_unit = 13
                });
                                                      
                request_handle:streamInfo():dynamicMetadata():set("envoy.filters.http.ratelimit.override", "_1a28dd355c704b393dd4d8b6e44a9d1f", {
                    unit = "MINUTE",
                    requests_per_unit = 178
                });                                                      
            end              
        """.smartTrimmed()
        )
    }

    private fun createGroupWithIncomingRateLimits(vararg rateLimitEndpoints: IncomingRateLimitEndpoint): Group =
        ServicesGroup(CommunicationMode.ADS, proxySettings = ProxySettings(incoming = Incoming(
            rateLimitEndpoints = rateLimitEndpoints.toList()
        )))

    private fun String.smartTrimmed(): Array<String> =
        lines()
            .map(String::trim)
            .filter(String::isNotBlank)
            .toTypedArray()
}
