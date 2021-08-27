package pl.allegro.tech.servicemesh.envoycontrol.permissions

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo1EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension
import javax.net.ssl.SSLException

class TlsClientCertRequiredTest {

    companion object {

        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(consul, mapOf(
            "envoy-control.envoy.snapshot.incoming-permissions.enabled" to true,
            "envoy-control.envoy.snapshot.incoming-permissions.overlapping-paths-fix" to true,
            "envoy-control.envoy.snapshot.incoming-permissions.tls-authentication.require-client-certificate" to true,
            "envoy-control.envoy.snapshot.outgoing-permissions.services-allowed-to-use-wildcard" to setOf("echo")
        ))

        @JvmField
        @RegisterExtension
        val service = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val envoy = EnvoyExtension(envoyControl, config = Echo1EnvoyAuthConfig, localService = service)
    }

    @Test
    @SuppressWarnings("SwallowedException")
    fun `should reject client without a certificate during TLS handshake`() {
        untilAsserted {
            // expects
            assertThrows<SSLException> {
                envoy.ingressOperations.callLocalServiceInsecure("/status/", useTls = true)
            }
            envoy.assertReportedPeerCertificateNotFoundError()
        }
    }

    private fun EnvoyExtension.assertReportedPeerCertificateNotFoundError() {
        val sslHandshakeErrors = this.container.admin().statValue("listener.0.0.0.0_5001.ssl.fail_verify_no_cert")?.toInt()
        assertThat(sslHandshakeErrors).isGreaterThan(0)
    }
}
