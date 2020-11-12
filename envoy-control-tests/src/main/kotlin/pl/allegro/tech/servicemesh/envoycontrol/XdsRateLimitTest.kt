package pl.allegro.tech.servicemesh.envoycontrol

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo1EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension

class XdsRateLimitTest {
    companion object {

        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(
            consul, mapOf(
                "envoy-control.envoy.snapshot.incoming-permissions.enabled" to true,
                "envoy-control.envoy.snapshot.outgoing-permissions.services-allowed-to-use-wildcard" to setOf("echo")
            )
        )

        @JvmField
        @RegisterExtension
        val service = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val envoy = EnvoyExtension(envoyControl, service, config = Echo1EnvoyAuthConfig.copy(configOverride = """
            dynamic_resources:
              ads_config:
                rate_limit_settings:
                  max_tokens: 10
                  fill_rate: 2
        """.trimIndent()))
    }

    @Test
    fun `should rate limit rejected xds updates`() {
        // given
        consul.server.operations.registerService(service, name = "echo")
        consul.server.operations.registerService(service, name = "echo2")
        consul.server.operations.registerService(service, name = "echo3")
        Thread.sleep(1000000)
    }
}
