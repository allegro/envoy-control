package pl.allegro.tech.servicemesh.envoycontrol.permissions

import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isForbidden
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isFrom
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isOk
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isUnreachable
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.ClientsFactory
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo1EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo2EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo3EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.CallStats
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyContainer
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.EndpointMatch
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoContainer
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension

internal class TlsBasedAuthenticationTest {

    companion object {

        private val ecProperties = mapOf(
            "envoy-control.envoy.snapshot.incoming-permissions.enabled" to true,
            "envoy-control.envoy.snapshot.incoming-permissions.tls-authentication.services-allowed-to-use-wildcard" to listOf("echo3"),
            "envoy-control.envoy.snapshot.outgoing-permissions.services-allowed-to-use-wildcard" to setOf("echo"),
            "envoy-control.envoy.snapshot.routes.status.create-virtual-cluster" to true,
            "envoy-control.envoy.snapshot.routes.status.endpoints" to mutableListOf(EndpointMatch().also { it.path = "/status/" }),
            "envoy-control.envoy.snapshot.routes.status.enabled" to true,
            "envoy-control.envoy.snapshot.routes.status.path-prefix" to "/status/",
            // Round robin gives much more predictable results in tests than LEAST_REQUEST
            "envoy-control.envoy.snapshot.load-balancing.policy" to "ROUND_ROBIN"
        )

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
                  outgoing:
                    dependencies:
                      - service: "echo3"
        """.trimIndent())

        // language=yaml
        val envoyContainerWithEcho3SanConfig = Echo3EnvoyAuthConfig.copy(configOverride = """
            node:
              metadata:
                proxy_settings:
                  outgoing:
                    dependencies:
                      - service: "echo2"
        """.trimIndent())

        // language=yaml
        private val echo3EnvoyConfigWildcard = Echo3EnvoyAuthConfig.copy(configOverride = """
            node:
              metadata:
                proxy_settings:
                  incoming:
                    endpoints:
                      - path: "/secured_endpoint"
                        clients: ["*"]
        """.trimIndent())

        private val envoyNotTrustingCa = Echo1EnvoyAuthConfig.copy(
                // do not trust default CA used by other Envoys
                trustedCa = "/app/root-ca2.crt"
        )

        private val envoyDifferentCaConfig = Echo1EnvoyAuthConfig.copy(
                // certificate not signed by default CA
                certificateChain = "/app/fullchain_echo_root-ca2.pem"
        )

        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(consul, ecProperties)

        @JvmField
        @RegisterExtension
        val service1 = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val service2 = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val service3 = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val echo1Envoy = EnvoyExtension(envoyControl, service1, echo1EnvoyConfig)

        @JvmField
        @RegisterExtension
        val echo2Envoy = EnvoyExtension(envoyControl, service2, echo2EnvoyConfig)

        @JvmField
        @RegisterExtension
        val envoyNotTrustingDefaultCa = EnvoyExtension(envoyControl, service2, envoyNotTrustingCa)

        @JvmField
        @RegisterExtension
        val envoyDifferentCa = EnvoyExtension(envoyControl, service2, envoyDifferentCaConfig)

        @JvmField
        @RegisterExtension
        val envoyContainerWithEcho3San = EnvoyExtension(envoyControl, service2, envoyContainerWithEcho3SanConfig)

        @JvmField
        @RegisterExtension
        val envoyContainerWithWildcardPrincipal = EnvoyExtension(envoyControl, service1, echo3EnvoyConfigWildcard)

        private val insecureClient = ClientsFactory.createInsecureClient()

        private fun registerEcho2WithEnvoyOnIngress() {
            consul.server.operations.registerService(
                    id = "echo2",
                    name = "echo2",
                    address = echo2Envoy.container.ipAddress(),
                    port = EnvoyContainer.INGRESS_LISTENER_CONTAINER_PORT,
                    tags = listOf("mtls:enabled")
            )
        }

        private fun registerEcho2EnvoyAsEcho3() {
            consul.server.operations.registerService(
                    id = "echo3",
                    name = "echo3",
                    address = echo2Envoy.container.ipAddress(),
                    port = EnvoyContainer.INGRESS_LISTENER_CONTAINER_PORT,
                    tags = listOf("mtls:enabled")
            )
        }

        private fun registerEcho2Insecure() {
            consul.server.operations.registerService(
                    id = "echo2_not_secure",
                    name = "echo2",
                    address = service1.container().ipAddress(),
                    port = EchoContainer.PORT,
                    tags = listOf()
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
            val sslHandshakes = echo1Envoy.container.admin().statValue("cluster.echo2.ssl.handshake")?.toInt()
            assertThat(sslHandshakes).isGreaterThan(0)

            val sslConnections = echo2Envoy.container.admin().statValue("http.ingress_https.downstream_cx_ssl_total")?.toInt()
            assertThat(sslConnections).isGreaterThan(0)

            assertThat(validResponse).isOk().isFrom(service2)
        }
    }

    @Test
    fun `should encrypt traffic between selected services even if only one endpoint supports mtls`() {
        // given 2 endpoints
        registerEcho2Insecure()
        untilAsserted {
            val echo2endpoints = echo1Envoy.container.admin().cluster("echo2")?.hostStatuses?.size ?: 0
            assertThat(echo2endpoints).isEqualTo(2)
        }

        // when
        val callStats = echo1Envoy.egressOperations.callServiceRepeatedly(
                service = "echo2",
                pathAndQuery = "/secured_endpoint",
                assertNoErrors = true,
                minRepeat = 2,
                maxRepeat = 2,
                stats = CallStats(listOf(service1.container(), service2.container()))
        )

        // then
        assertThat(callStats.failedHits).isEqualTo(0)
        assertThat(callStats.hits(service1.container())).isEqualTo(1)
        assertThat(callStats.hits(service2.container())).isEqualTo(1)

        val defaultToPlaintextMatchesCount = echo1Envoy.container.admin().statValue("cluster.echo2.plaintext_match.total_match_count")?.toInt()
        assertThat(defaultToPlaintextMatchesCount).isEqualTo(1)

        val enableMTLSMatchesCount = echo1Envoy.container.admin().statValue("cluster.echo2.mtls_match.total_match_count")?.toInt()
        assertThat(enableMTLSMatchesCount).isEqualTo(1)
    }

    @Test
    fun `should not allow traffic that fails client SAN validation`() {
        untilAsserted {
            // when
            // echo2 doesn't allow requests from echo3
            val invalidResponse = callEcho2(from = envoyContainerWithEcho3San)

            // then
            val sanValidationFailure = echo2Envoy.container.admin().statValue("http.ingress_https.rbac.denied")?.toInt()
            assertThat(sanValidationFailure).isGreaterThan(0)
            assertThat(invalidResponse).isForbidden()
        }
    }

    @Test
    fun `should not allow traffic that fails server SAN validation`() {
        // echo2 tries to impersonate as echo3
        registerEcho2EnvoyAsEcho3()

        untilAsserted {
            // when
            val invalidResponse = callEcho3FromEcho1()

            // then
            val sanValidationFailure = echo1Envoy.container.admin().statValue("cluster.echo3.ssl.fail_verify_san")?.toInt()
            assertThat(sanValidationFailure).isGreaterThan(0)
            assertThat(invalidResponse).isUnreachable()
        }
    }

    @Test
    fun `client should reject server certificate signed by not trusted CA`() {
        untilAsserted {
            // when
            val invalidResponse = callEcho2(from = envoyNotTrustingDefaultCa)

            // then
            val serverTlsErrors = echo2Envoy.container.admin().statValue("listener.0.0.0.0_5001.ssl.connection_error")?.toInt()
            assertThat(serverTlsErrors).isGreaterThan(0)

            // then
            val clientVerificationErrors = envoyNotTrustingDefaultCa.container.admin().statValue("cluster.echo2.ssl.fail_verify_error")?.toInt()
            assertThat(clientVerificationErrors).isGreaterThan(0)
            assertThat(invalidResponse).isUnreachable()
        }
    }

    @Test
    fun `server should reject client certificate signed by not trusted CA`() {
        untilAsserted {
            // when
            val invalidResponse = callEcho2(from = envoyDifferentCa)

            // then
            val serverVerificationErrors = echo2Envoy.container.admin().statValue("listener.0.0.0.0_5001.ssl.fail_verify_error")?.toInt()
            assertThat(serverVerificationErrors).isGreaterThan(0)

            // then
            val clientTlsErrors = envoyDifferentCa.container.admin().statValue("cluster.echo2.ssl.connection_error")?.toInt()
            assertThat(clientTlsErrors).isGreaterThan(0)
            assertThat(invalidResponse).isUnreachable()
        }
    }

    @Test
    @SuppressWarnings("SwallowedException")
    fun `should reject client without a certificate during RBAC verification`() {
        untilAsserted {
            // when
            val invalidResponse = callEcho2IngressUsingClientWithoutCertificate()

            // then
            val sanValidationFailure = echo2Envoy.container.admin().statValue("http.ingress_https.rbac.denied")?.toInt()
            assertThat(sanValidationFailure).isGreaterThan(0)
            assertThat(invalidResponse).isForbidden()
        }
    }

    @Test
    fun `should allow client with wildcard in incoming permissions to be called from all authenticated clients`() {
        consul.server.operations.registerService(
                address = envoyContainerWithWildcardPrincipal.container.ipAddress(),
                port = EnvoyContainer.INGRESS_LISTENER_CONTAINER_PORT,
                id = "echo3",
                name = "echo3",
                tags = listOf("mtls:enabled")
        )
        untilAsserted {
            // when
            val validResponse1 = callEcho3FromEcho1()
            val validResponse2 = callEcho3FromEcho2()
            val invalidResponse1 = callEcho3FromEchoWithDifferentCa()
            val invalidResponse2 = callEcho3FromEchoWithNotTrustingDefaultCa()

            // then
            assertThat(validResponse1).isOk()
            assertThat(validResponse2).isOk()
            assertThat(invalidResponse1).isUnreachable()
            assertThat(invalidResponse2).isUnreachable()
        }
    }

    private fun callEcho2IngressUsingClientWithoutCertificate(): Response {
        val address = echo2Envoy.container.ingressListenerUrl(secured = true)
        val request = insecureClient.newCall(
                Request.Builder()
                        .method("GET", null)
                        .url(HttpUrl.get(address).newBuilder("/secured_endpoint")!!.build())
                        .build()
        )

        return request.execute()
    }

    private fun callEcho2FromEcho1() =
        echo1Envoy.egressOperations.callService("echo2", pathAndQuery = "/secured_endpoint")

    private fun callEcho2(from: EnvoyExtension) = from.egressOperations.callService("echo2", pathAndQuery = "/secured_endpoint")

    private fun callEcho3FromEcho1() =
        echo1Envoy.egressOperations.callService("echo3", pathAndQuery = "/secured_endpoint")

    private fun callEcho3FromEcho2() =
        echo2Envoy.egressOperations.callService("echo3", pathAndQuery = "/secured_endpoint")

    private fun callEcho3FromEchoWithNotTrustingDefaultCa() =
        envoyNotTrustingDefaultCa.egressOperations.callService("echo3", pathAndQuery = "/secured_endpoint")

    private fun callEcho3FromEchoWithDifferentCa() =
        envoyDifferentCa.egressOperations.callService("echo3", pathAndQuery = "/secured_endpoint")
}
