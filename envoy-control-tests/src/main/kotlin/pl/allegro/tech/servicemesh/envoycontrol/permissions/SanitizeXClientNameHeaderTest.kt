package pl.allegro.tech.servicemesh.envoycontrol.permissions

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyContainer
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension

class SanitizeXClientNameHeaderTest {
    companion object {

        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(consul, mapOf(
            "envoy-control.envoy.snapshot.local-service.retry-policy.per-http-method.GET.enabled" to true,
            "envoy-control.envoy.snapshot.local-service.retry-policy.per-http-method.GET.retry-on" to listOf("connect-failure", "reset"),
            "envoy-control.envoy.snapshot.local-service.retry-policy.per-http-method.GET.num-retries" to 3
        ))

        @JvmField
        @RegisterExtension
        val service = EchoServiceExtension()

        private val Echo1EnvoyAuthConfig = EnvoyConfig("envoy/config_auth.yaml")

        // language=yaml
        private val proxySettings = """
            node:
              metadata:
                proxy_settings:
                  incoming:
                    unlistedEndpointsPolicy: "blockAndLog"
                    endpoints:
                    - path: "/log-unlisted-clients"
                      methods: [GET]
                      clients: ["echo2"]
                      unlistedClientsPolicy: log
                  outgoing:
                    dependencies:
                      - service: "echo2"
        """.trimIndent()

        @JvmField
        @RegisterExtension
        val envoy = EnvoyExtension(envoyControl, service, Echo1EnvoyAuthConfig.copy(configOverride = proxySettings))

        @JvmField
        @RegisterExtension
        val envoySecond = EnvoyExtension(envoyControl, service)
    }

    @BeforeEach
    fun beforeEach() {
        consul.server.operations.registerService(
            id = "echo",
            name = "echo",
            address = envoy.container.ipAddress(),
            port = EnvoyContainer.INGRESS_LISTENER_CONTAINER_PORT,
            tags = listOf("mtls:enabled")
        )

        waitForEnvoysInitialized()
    }

    private fun waitForEnvoysInitialized() {
        untilAsserted {
            assertThat(envoySecond.container.admin().isEndpointHealthy("echo", envoy.container.ipAddress())).isTrue()
        }
    }

    @Test
    fun `should always remove "x-client-name-trusted" header`() {
        // when
        val headers = mapOf("x-client-name-trusted" to "fake-service")
        val response = envoySecond.egressOperations.callService("echo", headers, "/log-unlisted-clients")
        // then
        assertThat(response.header("x-client-name-trusted")).isNull()
    }
}
