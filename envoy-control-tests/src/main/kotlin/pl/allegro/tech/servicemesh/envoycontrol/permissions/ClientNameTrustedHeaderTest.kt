package pl.allegro.tech.servicemesh.envoycontrol.permissions

import okhttp3.Headers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isOk
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo1EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo2EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyContainer
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.GenericServiceExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.HttpsEchoContainer
import pl.allegro.tech.servicemesh.envoycontrol.config.service.HttpsEchoResponse
import pl.allegro.tech.servicemesh.envoycontrol.config.service.isFrom
import java.time.Duration

class ClientNameTrustedHeaderTest {
    companion object {

        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(consul, mapOf(
            "envoy-control.envoy.snapshot.incoming-permissions.tls-authentication.require-client-certificate" to false,
            "envoy-control.envoy.snapshot.incoming-permissions.trusted-client-identity-header" to "x-client-name-trusted",
            "envoy-control.envoy.snapshot.incoming-permissions.tls-authentication.san-uri-format" to "spiffe://{service-name}",
            "envoy-control.envoy.snapshot.incoming-permissions.tls-authentication.service-name-wildcard-regex" to ".+"
        ))

        @JvmField
        @RegisterExtension
        val service = GenericServiceExtension(HttpsEchoContainer())

        // language=yaml
        private var proxySettings = """
            node:
              metadata:
                proxy_settings:
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
                  outgoing:
                    dependencies:
                      - service: "echo"
        """.trimIndent()

        @JvmField
        @RegisterExtension
        val envoy2 = EnvoyExtension(envoyControl, service, Echo2EnvoyAuthConfig.copy(configOverride = echoClientsConfig))

        val echo4EnvoyAuthConfig = Echo1EnvoyAuthConfig.copy(
            serviceName = "echo4",
            certificateChain = "/app/fullchain_echo4.pem",
            privateKey = "/app/privkey_echo4.pem",
            configOverride = echoClientsConfig
        )

        @JvmField
        @RegisterExtension
        val envoy4MultipleSANs = EnvoyExtension(envoyControl, service, echo4EnvoyAuthConfig)

        val echo5EnvoyAuthConfig = Echo1EnvoyAuthConfig.copy(
            serviceName = "echo5",
            certificateChain = "/app/fullchain_echo5.pem",
            privateKey = "/app/privkey_echo5.pem",
            configOverride = echoClientsConfig
        )

        @JvmField
        @RegisterExtension
        val envoy5InvalidSANs = EnvoyExtension(envoyControl, service, echo5EnvoyAuthConfig)
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
        untilAsserted(wait = Duration.ofSeconds(20)) {
            assertThat(envoy2.container.admin().isEndpointHealthy("echo", envoy.container.ipAddress())).isTrue()
            assertThat(envoy4MultipleSANs.container.admin().isEndpointHealthy("echo", envoy.container.ipAddress())).isTrue()
            assertThat(envoy5InvalidSANs.container.admin().isEndpointHealthy("echo", envoy.container.ipAddress())).isTrue()
        }
    }

    @Test
    fun `should always remove "x-client-name-trusted" header on every envoy ingress request`() {
        // when
        val response = envoy2.ingressOperations.callLocalService(
            "/endpoint",
            Headers.of(mapOf("x-client-name-trusted" to "fake-service"))
        )

        // then
        assertThat(response).isOk()
        HttpsEchoResponse(response).also {
            assertThat(it).isFrom(service.container())
            assertThat(it.requestHeaders["x-client-name-trusted"]).isNull()
        }
    }

    @Test
    fun `should add "x-client-name-trusted" header on envoy ingress request`() {
        // when
        val response = envoy2.egressOperations.callService("echo", emptyMap(), "/endpoint")

        // then
        assertThat(response).isOk()
        HttpsEchoResponse(response).also {
            assertThat(it).isFrom(service.container())
            assertThat(it.requestHeaders["x-client-name-trusted"]).isEqualTo("echo2")
        }

    }

    @Test
    fun `should override "x-client-name-trusted" header with trusted client name form certificate on request`() {
        // when
        val headers = mapOf("x-client-name-trusted" to "fake-service")
        val response = envoy2.egressOperations.callService("echo", headers, "/endpoint")

        // then
        assertThat(response).isOk()
        HttpsEchoResponse(response).also {
            assertThat(it).isFrom(service.container())
            assertThat(it.requestHeaders["x-client-name-trusted"]).isEqualTo("echo2")
        }
    }

    @Test
    fun `should set "x-client-name-trusted" header based on all URIs in certificate SAN field`() {
        // when
        val response = envoy4MultipleSANs.egressOperations.callService("echo", emptyMap(), "/endpoint")

        // then
        assertThat(response).isOk()
        HttpsEchoResponse(response).also {
            assertThat(it).isFrom(service.container())
            assertThat(it.requestHeaders["x-client-name-trusted"]).isEqualTo("echo4, echo4-special, echo4-admin")
        }
    }

    @Test
    fun `should not set "x-client-name-trusted" header based on URIs in certificate SAN fields in invalid format`() {
        // when
        val response = envoy5InvalidSANs.egressOperations.callService("echo", emptyMap(), "/endpoint")

        // then
        assertThat(response).isOk()
        HttpsEchoResponse(response).also {
            assertThat(it).isFrom(service.container())
            assertThat(it.requestHeaders["x-client-name-trusted"]).isNull()
        }
    }
}
