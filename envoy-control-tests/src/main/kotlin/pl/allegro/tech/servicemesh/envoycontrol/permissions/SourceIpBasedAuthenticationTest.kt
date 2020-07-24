package pl.allegro.tech.servicemesh.envoycontrol.permissions

import okhttp3.Headers
import okhttp3.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo1EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo2EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlRunnerTestApp
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyContainer
import pl.allegro.tech.servicemesh.envoycontrol.config.containers.ToxiproxyContainer

internal class SourceIpBasedAuthenticationTest : EnvoyControlTestConfiguration() {

    companion object {
        @Container
        val loremContainer = ToxiproxyContainer(exposedPortsCount = 1).withNetwork(network)
        @Container
        val ipsumContainer = ToxiproxyContainer(exposedPortsCount = 1).withNetwork(network)

        private const val prefix = "envoy-control.envoy.snapshot"
        private val properties = { mapOf(
            "$prefix.incoming-permissions.enabled" to true,
            "$prefix.outgoing-permissions.services-allowed-to-use-wildcard" to setOf("echo"),
            "$prefix.incoming-permissions.source-ip-authentication.ip-from-service-discovery.enabled-for-incoming-services" to
                    listOf("echo"),
            "$prefix.incoming-permissions.source-ip-authentication.ip-from-range.lorem" to "${loremContainer.ipAddress()}/32",
            "$prefix.routes.status.create-virtual-cluster" to true,
            "$prefix.routes.status.path-prefix" to "/status/",
            "$prefix.routes.status.enabled" to true
        ) }

        // language=yaml
        private val echo2EnvoyConfig = Echo2EnvoyAuthConfig.copy(configOverride = """
            node:
              metadata:
                proxy_settings:
                  incoming:
                    endpoints:
                      - path: "/secured_endpoint"
                        clients: ["echo", "lorem"]
        """.trimIndent())

        @JvmStatic
        @BeforeAll
        fun setupTest() {
            setup(appFactoryForEc1 = { consulPort ->
                EnvoyControlRunnerTestApp(properties = properties(), consulPort = consulPort)
            }, envoyConfig = Echo1EnvoyAuthConfig, secondEnvoyConfig = echo2EnvoyConfig, envoys = 2)
        }
    }

    @Test
    fun `should allow access to selected clients using ip-from-discovery based authentication over plain http`() {
        registerEcho1WithEnvoy1OnIngress()
        registerEcho2WithEnvoy2OnIngress()

        untilAsserted {
            envoyContainer2.admin().resetCounters()

            // when
            val requestFromEcho1Response = callEcho2ThroughEnvoy1()
            val directRequestResponse = callEcho2ThroughEnvoy2Ingress()

            val plainHttpAccessDenials = envoyContainer2.admin().statValue("http.ingress_http.rbac.denied")?.toInt()
            val sslHandshakes = envoyContainer2.admin().statValue("listener.0.0.0.0_5001.ssl.handshake")?.toInt()

            // then
            assertThat(requestFromEcho1Response).isOk().isFrom(echoContainer2)
            assertThat(directRequestResponse).isForbidden()

            assertThat(sslHandshakes).isZero()
            assertThat(plainHttpAccessDenials).isOne()
        }
    }

    @Test
    fun `should allow access to selected clients using ip-from-range based authentication over plain http`() {
        // given
        val loremToEcho2Proxy = loremContainer.createProxyToEnvoyIngress(envoy = envoyContainer2)
        val ipsumToEcho2Proxy = ipsumContainer.createProxyToEnvoyIngress(envoy = envoyContainer2)

        // when
        val requestFromLoremResponse = callEcho2(from = loremToEcho2Proxy)
        val requestFromIpsumResponse = callEcho2(from = ipsumToEcho2Proxy)

        val plainHttpAccessDenials = envoyContainer2.admin().statValue("http.ingress_http.rbac.denied")?.toInt()
        val sslHandshakes = envoyContainer2.admin().statValue("listener.0.0.0.0_5001.ssl.handshake")?.toInt()

        // then
        assertThat(requestFromLoremResponse).isOk().isFrom(echoContainer2)
        assertThat(requestFromIpsumResponse).isForbidden()

        assertThat(sslHandshakes).isZero()
        assertThat(plainHttpAccessDenials).isOne()
    }

    private fun registerEcho1WithEnvoy1OnIngress() {
        registerService(
                id = "echo",
                name = "echo", address = envoyContainer1.ipAddress(),
                port = EnvoyContainer.INGRESS_LISTENER_CONTAINER_PORT
        )
    }

    private fun registerEcho2WithEnvoy2OnIngress() {
        registerService(
                id = "echo2",
                name = "echo2", address = envoyContainer2.ipAddress(),
                port = EnvoyContainer.INGRESS_LISTENER_CONTAINER_PORT
        )
    }

    private fun callEcho2(from: String): Response = call(address = from, pathAndQuery = "/secured_endpoint")

    private fun callEcho2ThroughEnvoy2Ingress(): Response =
        callLocalService("/secured_endpoint", Headers.of(), envoyContainer2)

    private fun callEcho2ThroughEnvoy1() = callService(service = "echo2", pathAndQuery = "/secured_endpoint")
}
