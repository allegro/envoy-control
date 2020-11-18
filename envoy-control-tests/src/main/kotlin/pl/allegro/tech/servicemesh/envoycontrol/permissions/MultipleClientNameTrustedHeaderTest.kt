package pl.allegro.tech.servicemesh.envoycontrol.permissions

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo1EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyContainer
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension

class MultipleClientNameTrustedHeaderTest {
    companion object {

        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(consul, mapOf(
            "envoy-control.envoy.snapshot.trustedCaFile" to "/app/root-ca-3.crt",
            "envoy-control.envoy.snapshot.local-service.retry-policy.per-http-method.GET.enabled" to true,
            "envoy-control.envoy.snapshot.local-service.retry-policy.per-http-method.GET.retry-on" to listOf("connect-failure", "reset"),
            "envoy-control.envoy.snapshot.local-service.retry-policy.per-http-method.GET.num-retries" to 3
        ))

        @JvmField
        @RegisterExtension
        val service = EchoServiceExtension()

        // language=yaml
        val proxySettings = """
            node:
              metadata:
                proxy_settings:
                  incoming:
                    unlistedEndpointsPolicy: log
                    endpoints:
                    - path: "/log-unlisted-clients"
                      methods: [GET]
                      clients: ["echo5"]
                      unlistedClientsPolicy: log
                  outgoing:
                    dependencies:
                      - service: "echo5"
        """.trimIndent()

        @JvmField
        @RegisterExtension
        val envoy = EnvoyExtension(envoyControl, service,
            Echo1EnvoyAuthConfig.copy(
                trustedCa = "/app/root-ca-3.crt",
                serviceName = "echo4",
                certificateChain = "/app/fullchain_echo4.pem",
                privateKey = "/app/privkey_echo4.pem",
                configOverride = proxySettings
            )
        )

        // language=yaml
        private val echo5Config = """
            node:
              metadata:
                proxy_settings:
                  outgoing:
                    dependencies:
                      - service: "echo"
        """.trimIndent()

        val Echo5EnvoyAuthConfig = Echo1EnvoyAuthConfig.copy(
            trustedCa = "/app/root-ca-3.crt",
            serviceName = "echo5",
            certificateChain = "/app/fullchain_echo5.pem",
            privateKey = "/app/privkey_echo5.pem"
        )

        @JvmField
        @RegisterExtension
        val envoyFive = EnvoyExtension(envoyControl, service, Echo5EnvoyAuthConfig.copy(configOverride = echo5Config))
    }

    @BeforeEach
    fun beforeEach() {
        consul.server.operations.registerService(
            id = "echo4",
            name = "echo4",
            address = envoy.container.ipAddress(),
            port = EnvoyContainer.INGRESS_LISTENER_CONTAINER_PORT,
            tags = listOf("mtls:enabled")
        )

        waitForEnvoysInitialized()
    }

    private fun waitForEnvoysInitialized() {
        untilAsserted {
            assertThat(envoyFive.container.admin().isEndpointHealthy("echo4", envoy.container.ipAddress())).isTrue()
        }
    }

    @Test
    fun `should set "x-client-name-trusted" header based on URIs in certificate san field`() {
        untilAsserted {
            // when
            val response = envoyFive.egressOperations.callService("echo4", emptyMap(), "/log-unlisted-clients")
            // then
            assertThat(response.header("x-client-name-trusted")).isEqualTo("echo5,echo4-special,echo4-admin")
        }
    }
}
