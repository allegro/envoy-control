package pl.allegro.tech.servicemesh.envoycontrol.permissions

import okhttp3.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo1EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo2EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlRunnerTestApp
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyContainer
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

internal class TlsBasedAuthenticationTest : EnvoyControlTestConfiguration() {

    companion object {

        private val properties = mapOf(
            "envoy-control.envoy.snapshot.incoming-permissions.enabled" to true,
            "envoy-control.envoy.snapshot.outgoing-permissions.services-allowed-to-use-wildcard" to setOf("echo"),
            "envoy-control.envoy.snapshot.routes.status.create-virtual-cluster" to true,
            "envoy-control.envoy.snapshot.routes.status.path-prefix" to "/status/",
            "envoy-control.envoy.snapshot.routes.status.enabled" to true
        )

        val echo1Envoy by lazy { envoyContainer1 }
        val echo2Envoy by lazy { envoyContainer2 }

        // language=yaml
        private val echo1EnvoyConfig = Echo1EnvoyAuthConfig.copy(configOverride = """
            node:
              metadata:
                proxy_settings:
                  outgoing:
                    dependencies:
                      - service: "echo2"
                      - service: "echo3"
        """.trimIndent())
        // language=yaml
        private val echo2EnvoyConfig = Echo2EnvoyAuthConfig.copy(configOverride = """
            node:
              metadata:
                proxy_settings:
                  incoming:
                    endpoints:
                      - path: "/secured_endpoint"
                        clients: ["echo"]
        """.trimIndent())

        @JvmStatic
        @BeforeAll
        fun setupTest() {
            setup(appFactoryForEc1 = { consulPort ->
                EnvoyControlRunnerTestApp(properties = properties, consulPort = consulPort)
            }, envoyConfig = echo1EnvoyConfig, secondEnvoyConfig = echo2EnvoyConfig, envoys = 2)
        }

        private fun registerEcho2WithEnvoyOnIngress() {
            registerService(
                    id = "echo2",
                    name = "echo2",
                    container = echo2Envoy,
                    port = EnvoyContainer.INGRESS_LISTENER_CONTAINER_PORT,
                    tags = listOf("mtls:enabled")
            )
        }

        private fun registerEcho2EnvoyAsEcho3() {
            registerService(
                    id = "echo3",
                    name = "echo3",
                    container = echo2Envoy,
                    port = EnvoyContainer.INGRESS_LISTENER_CONTAINER_PORT,
                    tags = listOf("mtls:enabled")
            )
        }
    }

    @BeforeEach
    fun setup() {
        registerEcho2WithEnvoyOnIngress()
    }

    @Test
    fun `should encrypt traffic between selected services`() {
        untilAsserted {
            // when
            val validResponse = callEcho2FromEcho1()

            // then
            val sslHandshakes = echo1Envoy.admin().statValue("cluster.echo2.ssl.handshake")?.toInt()
            assertThat(sslHandshakes).isGreaterThan(0)

            val sslConnections = echo2Envoy.admin().statValue("http.ingress_https.downstream_cx_ssl_total")?.toInt()
            assertThat(sslConnections).isGreaterThan(0)

            assertThat(validResponse).isOk().isFrom(echoContainer2)
        }
    }

    @Test
    fun `should not allow traffic that fails client SAN validation`() {
        val echo3Envoy = createEnvoyContainerWithEcho3San()
        echo3Envoy.start()

        untilAsserted {
            // when
            // echo2 doesn't allow requests from echo3
            val invalidResponse = callEcho2(from = echo3Envoy)

            // then
            val sanValidationFailure = echo2Envoy.admin().statValue("http.ingress_https.rbac.denied")?.toInt()
            assertThat(sanValidationFailure).isGreaterThan(0)
            assertThat(invalidResponse).isForbidden()
        }

        echo3Envoy.stop()
    }

    @Test
    fun `should not allow traffic that fails server SAN validation`() {
        // echo2 tries to impersonate as echo3
        registerEcho2EnvoyAsEcho3()

        untilAsserted {
            // when
            val invalidResponse = callEcho3FromEcho1()

            // then
            val sanValidationFailure = echo1Envoy.admin().statValue("cluster.echo3.ssl.fail_verify_san")?.toInt()
            assertThat(sanValidationFailure).isGreaterThan(0)
            assertThat(invalidResponse).isUnreachable()
        }
    }

    @Test
    fun `client should reject server certificate signed by not trusted CA`() {
        val envoyDifferentCa = createEnvoyNotTrustingDefaultCA()

        untilAsserted {
            // when
            val invalidResponse = callEcho2(from = envoyDifferentCa)

            // then
            val serverTlsErrors = echo2Envoy.admin().statValue("listener.0.0.0.0_5001.ssl.connection_error")?.toInt()
            assertThat(serverTlsErrors).isGreaterThan(0)

            // then
            val clientVerificationErrors = envoyDifferentCa.admin().statValue("cluster.echo2.ssl.fail_verify_error")?.toInt()
            assertThat(clientVerificationErrors).isGreaterThan(0)
            assertThat(invalidResponse).isUnreachable()
        }

        envoyDifferentCa.stop()
    }

    @Test
    fun `server should reject client certificate signed by not trusted CA`() {
        val envoyDifferentCa = createEnvoyNotSignedByDefaultCA()

        untilAsserted {
            // when
            val invalidResponse = callEcho2(from = envoyDifferentCa)

            // then
            val serverVerificationErrors = echo2Envoy.admin().statValue("listener.0.0.0.0_5001.ssl.fail_verify_error")?.toInt()
            assertThat(serverVerificationErrors).isGreaterThan(0)

            // then
            val clientTlsErrors = envoyDifferentCa.admin().statValue("cluster.echo2.ssl.connection_error")?.toInt()
            assertThat(clientTlsErrors).isGreaterThan(0)
            assertThat(invalidResponse).isUnreachable()
        }

        envoyDifferentCa.stop()
    }

    @Test
    @SuppressWarnings("SwallowedException")
    fun `should reject client without a certificate`() {
        untilAsserted {
            var invalidResponse: Response? = null
            try {
                // when
                invalidResponse = callEcho2IngressUsingClientWithoutCertificate()

                // then
                assertThat(invalidResponse).isUnreachable()
            } catch (_: SSLPeerUnverifiedException) {
                assertEcho2EnvoyReportedNoPeerCertificateError()
            } catch (_: SSLHandshakeException) {
                assertEcho2EnvoyReportedNoPeerCertificateError()
            }
        }
    }

    private fun assertEcho2EnvoyReportedNoPeerCertificateError() {
        val sslHandshakeErrors = echo2Envoy.admin().statValue("listener.0.0.0.0_5001.ssl.fail_verify_no_cert")?.toInt()
        assertThat(sslHandshakeErrors).isGreaterThan(0)
    }

    private fun callEcho2IngressUsingClientWithoutCertificate(): Response {
        return callServiceInsecure(address = echo2Envoy.ingressListenerUrl(secured = true) + "/status/", service = "echo2")
    }

    private fun callEcho2FromEcho1() = call(service = "echo2", path = "/secured_endpoint", from = echo1Envoy)

    private fun callEcho2(from: EnvoyContainer) = call(service = "echo2", path = "/secured_endpoint", from = from)

    private fun callEcho3FromEcho1() = call(service = "echo3", path = "/secured_endpoint", from = echo1Envoy)

    private fun createEnvoyNotTrustingDefaultCA(): EnvoyContainer {
        val envoy = EnvoyContainer(
            Echo1EnvoyAuthConfig.copy(
                // do not trust default CA used by other Envoys
                trustedCa = "/app/root-ca2.crt"
            ),
            { localServiceContainer.ipAddress() },
            envoyControl1.grpcPort,
            image = defaultEnvoyImage
        ).withNetwork(network)
        envoy.start()
        return envoy
    }

    private fun createEnvoyNotSignedByDefaultCA(): EnvoyContainer {
        val envoy = EnvoyContainer(
            Echo1EnvoyAuthConfig.copy(
                // certificate not signed by default CA
                certificateChain = "/app/fullchain_echo_root-ca2.pem"
            ),
            { localServiceContainer.ipAddress() },
            envoyControl1.grpcPort,
            image = defaultEnvoyImage
        ).withNetwork(network)
        envoy.start()
        return envoy
    }

    private fun createEnvoyContainerWithEcho3San(): EnvoyContainer {
        // language=yaml
        val configOverride = """
            node:
              metadata:
                proxy_settings:
                  outgoing:
                    dependencies:
                      - service: "echo2"
        """.trimIndent()

        return createEnvoyContainerWithEcho3Certificate(configOverride = configOverride)
    }
}
