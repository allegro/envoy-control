package pl.allegro.tech.servicemesh.envoycontrol.permissions

import okhttp3.Headers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo1EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo2EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyContainer
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoHeadersContainer
import pl.allegro.tech.servicemesh.envoycontrol.config.service.GenericServiceExtension

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
        val service = GenericServiceExtension(EchoHeadersContainer())

        // language=yaml
        private var proxySettings = """
            node:
              metadata:
                proxy_settings:
                  incoming:
                    unlistedEndpointsPolicy: blockAndLog
                    endpoints:
                    - path: "/log-unlisted-clients"
                      clients: ["authorized-clients"]
                      unlistedClientsPolicy: blockAndLog
                    roles:
                    - name: authorized-clients
                      clients: ["echo2", "echo4", "echo5"]
                  outgoing:
                    dependencies: []
        """.trimIndent()

        @JvmField
        @RegisterExtension
        val envoy = EnvoyExtension(envoyControl, service,
            Echo1EnvoyAuthConfig.copy(configOverride = proxySettings)
        )

        // language=yaml
        private val echoClientsConfig = """
            node:
              metadata:
                proxy_settings:
                  incoming:
                    unlistedEndpointsPolicy: log
                  outgoing:
                    dependencies:
                      - service: "echo"
        """.trimIndent()

        @JvmField
        @RegisterExtension
        val envoy2 = EnvoyExtension(envoyControl, service, Echo2EnvoyAuthConfig.copy(configOverride = echoClientsConfig))

        val Echo4EnvoyAuthConfig = Echo1EnvoyAuthConfig.copy(
            serviceName = "echo4",
            certificateChain = "/app/fullchain_echo4.pem",
            privateKey = "/app/privkey_echo4.pem",
            configOverride = echoClientsConfig
        )

        @JvmField
        @RegisterExtension
        val envoy4 = EnvoyExtension(envoyControl, service, Echo4EnvoyAuthConfig.copy(configOverride = echoClientsConfig))

        val Echo5EnvoyAuthConfig = Echo1EnvoyAuthConfig.copy(
            serviceName = "echo5",
            certificateChain = "/app/fullchain_echo5.pem",
            privateKey = "/app/privkey_echo5.pem",
            configOverride = echoClientsConfig
        )

        @JvmField
        @RegisterExtension
        val envoy5 = EnvoyExtension(envoyControl, service, Echo5EnvoyAuthConfig.copy(configOverride = echoClientsConfig))
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
            assertThat(envoy2.container.admin().isEndpointHealthy("echo", envoy.container.ipAddress())).isTrue()
        }
    }

    @Test
    fun `should always remove "x-client-name-trusted" header on every envoy ingress request`() {
        untilAsserted {
            // when
            val response = envoy2.ingressOperations.callLocalService(
                "/log-unlisted-clients",
                Headers.of(mapOf("x-client-name-trusted" to "fake-service"))
            )
            // then
            assertThat(response.header("x-client-name-trusted")).isNull()
        }
    }

    @Test
    fun `should add "x-client-name-trusted" header on envoy ingress request`() {
        untilAsserted {
            // when
            val response = envoy2.egressOperations.callService("echo", emptyMap(), "/log-unlisted-clients")
            // then
            assertThat(response.header("x-client-name-trusted")).isEqualTo("echo2")
        }
    }

    @Test
    fun `should override "x-client-name-trusted" header with trusted client name form certificate on request`() {
        untilAsserted {
            // when
            val headers = mapOf("x-client-name-trusted" to "fake-service")
            val response = envoy2.egressOperations.callService("echo", headers, "/log-unlisted-clients")
            // then
            assertThat(response.header("x-client-name-trusted")).isEqualTo("echo2")
        }
    }

    @Test
    fun `should set "x-client-name-trusted" header based on all URIs in certificate SAN field`() {
        untilAsserted {
            // when
            val response = envoy4.egressOperations.callService("echo", emptyMap(), "/log-unlisted-clients")
            // then
            assertThat(response.header("x-client-name-trusted")).isEqualTo("echo4,echo4-special,echo4-admin")
        }
    }

    @Test
    fun `should set "x-client-name-trusted" header based on URIs in certificate SAN field regardles protocol used in SAN alt name`() {
        untilAsserted {
            // when
            val response = envoy5.egressOperations.callService("echo", emptyMap(), "/log-unlisted-clients")
            // then
            assertThat(response.header("x-client-name-trusted")).isEqualTo("echo5,echo5-special,echo5-admin")
        }
    }
}
