package pl.allegro.tech.servicemesh.envoycontrol.permissions

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isUnreachable
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo1EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

class TlsClientCertRequiredTest {

    companion object {

        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(consul, mapOf(
            "envoy-control.envoy.snapshot.incoming-permissions.enabled" to true,
            "envoy-control.envoy.snapshot.incoming-permissions.tls-authentication.require-client-certificate" to true,
            "envoy-control.envoy.snapshot.outgoing-permissions.services-allowed-to-use-wildcard" to setOf("echo")
        ))

        @JvmField
        @RegisterExtension
        val envoy = EnvoyExtension(envoyControl, config = Echo1EnvoyAuthConfig)
    }

    @Test
    @SuppressWarnings("SwallowedException")
    fun `should reject client without a certificate during TLS handshake`() {
        EnvoyControlTestConfiguration.untilAsserted {
            try {
                // when
                val invalidResponse = envoy.ingressOperations.callLocalServiceInsecure("/status/")

                // then
                assertThat(invalidResponse).isUnreachable()
            } catch (_: SSLPeerUnverifiedException) {
                envoy.assertReportedNoPeerCertificateError()
            } catch (_: SSLHandshakeException) {
                envoy.assertReportedNoPeerCertificateError()
            }
        }
    }

    private fun EnvoyExtension.assertReportedNoPeerCertificateError() {
        val sslHandshakeErrors = this.container.admin().statValue("listener.0.0.0.0_5001.ssl.fail_verify_no_cert")?.toInt()
        assertThat(sslHandshakeErrors).isGreaterThan(0)
    }
}
