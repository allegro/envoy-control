package pl.allegro.tech.servicemesh.envoycontrol.permissions

import okhttp3.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isUnreachable
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo1EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration.Companion.callServiceInsecure
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlRunnerTestApp
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

class TlsClientCertRequiredTest {

    companion object {
        private val properties = mapOf(
            "envoy-control.envoy.snapshot.incoming-permissions.enabled" to true,
            "envoy-control.envoy.snapshot.incoming-permissions.tls-authentication.require-client-certificate" to true,
            "envoy-control.envoy.snapshot.outgoing-permissions.services-allowed-to-use-wildcard" to setOf("echo")
        )

        val echo1Envoy by lazy { EnvoyControlTestConfiguration.envoyContainer1 }

        @JvmStatic
        @BeforeAll
        fun setupTest() {
            EnvoyControlTestConfiguration.setup(appFactoryForEc1 = { consulPort ->
                EnvoyControlRunnerTestApp(properties = properties, consulPort = consulPort)
            }, envoyConfig = Echo1EnvoyAuthConfig)
        }
    }

    @Test
    @SuppressWarnings("SwallowedException")
    fun `should reject client without a certificate during TLS handshake`() {
        EnvoyControlTestConfiguration.untilAsserted {
            var invalidResponse: Response? = null
            try {
                // when
                invalidResponse = callEcho2IngressUsingClientWithoutCertificate()

                // then
                assertThat(invalidResponse).isUnreachable()
            } catch (_: SSLPeerUnverifiedException) {
                assertEchoEnvoyReportedNoPeerCertificateError()
            } catch (_: SSLHandshakeException) {
                assertEchoEnvoyReportedNoPeerCertificateError()
            }
        }
    }

    private fun callEcho2IngressUsingClientWithoutCertificate(): Response {
        return callServiceInsecure(address = echo1Envoy.ingressListenerUrl(secured = true) + "/status/", service = "echo2")
    }

    private fun assertEchoEnvoyReportedNoPeerCertificateError() {
        val sslHandshakeErrors = echo1Envoy.admin().statValue("listener.0.0.0.0_5001.ssl.fail_verify_no_cert")?.toInt()
        assertThat(sslHandshakeErrors).isGreaterThan(0)
    }
}
