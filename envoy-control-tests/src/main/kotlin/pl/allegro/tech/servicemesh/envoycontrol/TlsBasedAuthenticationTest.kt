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

        private fun registerEcho3WithEnvoyOnIngress() {
            registerService(
                    id = "echo3",
                    name = "echo3",
                    container = envoyContainer1,
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

            val sslConnections = envoyContainer2.admin().statValue("http.ingress_https.downstream_cx_ssl_total")?.toInt()
            assertThat(sslConnections).isGreaterThan(0)

            assertThat(validResponse).isOk().isFrom(echoContainer2)
        }
    }

    @Test
    fun `should not allow traffic that fails client SAN validation`() {
        envoyContainerInvalidSan = createEnvoyContainerWithEcho3San()
        envoyContainerInvalidSan.start()

        untilAsserted {
            // when
            val invalidResponse = callEcho2ThroughEnvoyWithInvalidSan()

            // then
            val sanValidationFailure = envoyContainer2.admin().statValue("http.ingress_https.rbac.denied")?.toInt()
            assertThat(sanValidationFailure).isGreaterThan(0)
            assertThat(invalidResponse).isForbidden()
        }

        envoyContainerInvalidSan.stop()
    }

    @Test
    fun `should not allow traffic that fails server SAN validation`() {
        registerEcho3WithEnvoyOnIngress()

        untilAsserted {
            // when
            val invalidResponse = callEcho3ThroughEnvoy1()

            // then
            val sanValidationFailure = envoyContainer1.admin().statValue("cluster.echo3.ssl.fail_verify_san")?.toInt()
            assertThat(sanValidationFailure).isGreaterThan(0)
            assertThat(invalidResponse).isUnreachable()
        }

        envoyContainerInvalidSan.stop()
    }

    @Test
    fun `should not allow traffic from clients not signed by server certificate`() {
        val envoy1 = EnvoyContainer(
                Envoy1AuthConfig.filePath,
                localServiceContainer.ipAddress(),
                envoyControl1.grpcPort,
                image = defaultEnvoyImage,
                trustedCa = "/app/root-ca2.crt",
                certificateChain = "/app/fullchain_echo_root-ca2.pem"
        ).withNetwork(network)
        envoy1.start()

        untilAsserted {
            // when
            val invalidResponse = callService(address = envoy1.egressListenerUrl(), service = "echo2", pathAndQuery = "/secured_endpoint")

            // then
            val tlsErrors = envoyContainer2.admin().statValue("listener.0.0.0.0_5001.ssl.connection_error")?.toInt()
            assertThat(tlsErrors).isGreaterThan(0)

            // then
            val sslFailVerifyError = envoy1.admin().statValue("cluster.echo2.ssl.fail_verify_error")?.toInt()
            assertThat(sslFailVerifyError).isGreaterThan(0)
            assertThat(invalidResponse).isUnreachable()
        }

        envoy1.stop()
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
        return callServiceInsecure(address = envoyContainer2.ingressListenerUrl(secured = true) + "/status/", service = "echo2")
    }

    private fun callEcho2ThroughEnvoy1() = callService(service = "echo2", pathAndQuery = "/secured_endpoint")

    private fun callEcho3ThroughEnvoy1() = callService(service = "echo3", pathAndQuery = "/secured_endpoint")

    private fun callEcho2ThroughEnvoyWithInvalidSan(): Response {
        return callService(address = envoyContainerInvalidSan.egressListenerUrl(), service = "echo2", pathAndQuery = "/secured_endpoint")
    }
}
