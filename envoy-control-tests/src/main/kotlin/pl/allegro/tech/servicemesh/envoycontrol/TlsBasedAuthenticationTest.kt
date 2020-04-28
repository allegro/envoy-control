package pl.allegro.tech.servicemesh.envoycontrol

import okhttp3.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.config.Envoy1AuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.Envoy2AuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlRunnerTestApp
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyContainer
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

@SuppressWarnings("SwallowedException")
internal class TlsBasedAuthenticationTest : EnvoyControlTestConfiguration() {

    companion object {

        private val properties = mapOf(
            "envoy-control.envoy.snapshot.incoming-permissions.enabled" to true,
            "envoy-control.envoy.snapshot.incoming-permissions.tlsAuthentication.enabledForServices" to listOf("echo", "echo2"),
            "envoy-control.envoy.snapshot.routes.status.create-virtual-cluster" to true,
            "envoy-control.envoy.snapshot.routes.status.path-prefix" to "/status/",
            "envoy-control.envoy.snapshot.routes.status.enabled" to true
        )

        private lateinit var envoyContainerInvalidSan: EnvoyContainer

        @JvmStatic
        @BeforeAll
        fun setupTest() {
            setup(appFactoryForEc1 = { consulPort ->
                EnvoyControlRunnerTestApp(properties = properties, consulPort = consulPort)
            }, envoyConfig = Envoy1AuthConfig, secondEnvoyConfig = Envoy2AuthConfig, envoys = 2)
        }

        private fun registerEcho2WithEnvoyOnIngress() {
            registerService(
                    id = "echo2",
                    name = "echo2",
                    container = envoyContainer2,
                    port = EnvoyContainer.INGRESS_LISTENER_CONTAINER_PORT,
                    tags = listOf("mtls:enabled")
            )
        }
    }

    @BeforeEach
    fun setup() {
        registerService(name = "echo", tags = listOf("mtls:enabled"))
        registerEcho2WithEnvoyOnIngress()
    }

    @Test
    fun `should encrypt traffic between selected services`() {
        untilAsserted {
            // when
            val validResponse = callEcho2ThroughEnvoy1()

            // then
            val sslHandshakes = envoyContainer1.admin().statValue("cluster.echo2.ssl.handshake")?.toInt()
            assertThat(sslHandshakes).isGreaterThan(0)

            val sslConnections = envoyContainer2.admin().statValue("http.ingress_http.downstream_cx_ssl_total")?.toInt()
            assertThat(sslConnections).isGreaterThan(0)

            assertThat(validResponse).isOk().isFrom(echoContainer2)
        }
    }

    @Test
    fun `should not allow traffic that fails SAN validation`() {
        envoyContainerInvalidSan = createEnvoyContainerWithEcho2San()
        envoyContainerInvalidSan.start()

        untilAsserted {
            // when
            val invalidResponse = callEcho2ThroughEnvoyWithInvalidSan()

            // then
            val sanValidationFailure = envoyContainer2.admin().statValue("http.ingress_http.rbac.denied")?.toInt()
            assertThat(sanValidationFailure).isGreaterThan(0)
            assertThat(invalidResponse).isForbidden()
        }

        envoyContainerInvalidSan.stop()
    }

    @Test
    fun `should not allow unencrypted traffic between selected services`() {
        untilAsserted {
            var invalidResponse: Response? = null
            try {
                // when
                invalidResponse = callEcho2ThroughEnvoy2Ingress()

                // then
                assertThat(invalidResponse).isUnreachable()
            } catch (_: SSLPeerUnverifiedException) {
                assertEnvoyReportedSslError()
            } catch (_: SSLHandshakeException) {
                assertEnvoyReportedSslError()
            }
        }
    }

    private fun assertEnvoyReportedSslError() {
        val sslHandshakeErrors = envoyContainer2.admin().statValue("listener.0.0.0.0_5001.ssl.fail_verify_no_cert")?.toInt()
        assertThat(sslHandshakeErrors).isGreaterThan(0)
    }

    private fun callEcho2ThroughEnvoy2Ingress(): Response {
        return insecureCallService(address = envoyContainer2.ingressListenerUrl(secured = true) + "/status/", service = "echo2")
    }

    private fun callEcho2ThroughEnvoy1() = callService(service = "echo2", pathAndQuery = "/ip_endpoint")

    private fun callEcho2ThroughEnvoyWithInvalidSan(): Response {
        return callService(address = envoyContainerInvalidSan.egressListenerUrl(), service = "echo2", pathAndQuery = "/ip_endpoint")
    }
}
