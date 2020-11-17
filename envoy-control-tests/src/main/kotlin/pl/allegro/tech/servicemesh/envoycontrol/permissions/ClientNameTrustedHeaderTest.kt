package pl.allegro.tech.servicemesh.envoycontrol.permissions

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo1EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo2EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyContainer
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension

class ClientNameTrustedHeaderTest {
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

        // language=yaml
        private fun proxySettings(unlistedEndpointsPolicy: String) = """
            node:
              metadata:
                proxy_settings:
                  incoming:
                    unlistedEndpointsPolicy: $unlistedEndpointsPolicy
                    endpoints:
                    - path: "/block-unlisted-clients"
                      clients: ["authorized-clients"]
                      unlistedClientsPolicy: blockAndLog
                    - path: "/log-unlisted-clients"
                      methods: [GET]
                      clients: ["authorized-clients"]
                      unlistedClientsPolicy: log
                    - path: "/block-unlisted-clients-by-default"
                      clients: ["authorized-clients"]
                    roles:
                    - name: authorized-clients
                      clients: ["echo2", "source-ip-client"]
                  outgoing:
                    dependencies:
                      - service: "echo2"
        """.trimIndent()

        @JvmField
        @RegisterExtension
        val envoy = EnvoyExtension(envoyControl, service,
            Echo1EnvoyAuthConfig.copy(configOverride = proxySettings(unlistedEndpointsPolicy = "blockAndLog"))
        )

        // language=yaml
        private val echo2Config = """
            node:
              metadata:
                proxy_settings:
                  outgoing:
                    dependencies:
                      - service: "echo"
                      - service: "echo2"
        """.trimIndent()

        val Echo3EnvoyAuthConfig = Echo1EnvoyAuthConfig.copy(
            serviceName = "echo3",
            certificateChain = "/app/fullchain_echo2.pem",
            privateKey = "/app/privkey_echo2.pem"
        )

        @JvmField
        @RegisterExtension
        val envoySecond = EnvoyExtension(envoyControl, service, Echo2EnvoyAuthConfig.copy(configOverride = echo2Config))

        @JvmField
        @RegisterExtension
        val envoyThird = EnvoyExtension(envoyControl, service, Echo3EnvoyAuthConfig.copy(configOverride = echo2Config))
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
    fun `should return "x-client-name-trusted" header on request`() {
        // when
        val response = envoySecond.egressOperations.callService("echo", emptyMap(), "/log-unlisted-clients")
        // then
        assertThat(response.header("x-client-name-trusted")).isEqualTo("echo2")
    }

    @Test
    fun `should override "x-client-name-trusted" header with trusted client name form certificate on request`() {
        // when
        val headers = mapOf("x-client-name-trusted" to "fake-service")
        val response = envoySecond.egressOperations.callService("echo", headers, "/log-unlisted-clients")
        // then
        assertThat(response.header("x-client-name-trusted")).isEqualTo("echo2")
    }

    @Test
    fun `should not set "x-client-name-trusted" header if invalid cert provided`() {
        // when
        val response = envoyThird.egressOperations.callService("echo", emptyMap(), "/log-unlisted-clients")
        // then
        assertThat(response.header("x-client-name-trusted")).isNotEqualTo("echo2")
    }
}
