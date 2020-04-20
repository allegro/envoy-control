package pl.allegro.tech.servicemesh.envoycontrol

import org.apache.http.client.methods.CloseableHttpResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.config.Envoy1Ads
import pl.allegro.tech.servicemesh.envoycontrol.config.Envoy2Ads
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

        @JvmStatic
        @BeforeAll
        fun setupTest() {
            setup(appFactoryForEc1 = { consulPort ->
                EnvoyControlRunnerTestApp(properties = properties, consulPort = consulPort)
            }, envoyConfig = Envoy1Ads, secondEnvoyConfig = Envoy2Ads, envoys = 2)

            registerService(name = "echo")
            registerEcho2WithEnvoyOnIngress()
        }

        private fun registerEcho2WithEnvoyOnIngress() {
            registerService(
                    id = "echo2",
                    name = "echo2",
                    address = envoyContainer2.ipAddress(),
                    port = EnvoyContainer.INGRESS_LISTENER_CONTAINER_PORT
            )
        }
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
    fun `should not allow unencrypted traffic between selected services`() {
        untilAsserted {
            var invalidResponse: CloseableHttpResponse? = null
            try {
                // when
                invalidResponse = callEcho2ThroughEnvoy2Ingress()

                // then
                assertThat(invalidResponse.statusLine.statusCode).isEqualTo(503)
            } catch (e: SSLPeerUnverifiedException) {
                assertEnvoyReportedSslError()
            } catch (e: SSLHandshakeException) {
                assertEnvoyReportedSslError()
            } finally {
                invalidResponse?.close()
            }
        }
    }

    private fun assertEnvoyReportedSslError() {
        val sslHandshakeErrors = envoyContainer2.admin().statValue("listener.0.0.0.0_5001.ssl.fail_verify_no_cert")?.toInt()
        assertThat(sslHandshakeErrors).isGreaterThan(0)
    }

    private fun callEcho2ThroughEnvoy2Ingress(): CloseableHttpResponse {
        return insecureCall(url = envoyContainer2.ingressListenerUrl(secured = true) + "/status/")
    }

    private fun callEcho2ThroughEnvoy1() = callService(service = "echo2", pathAndQuery = "/ip_endpoint")
}
