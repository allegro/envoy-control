package pl.allegro.tech.servicemesh.envoycontrol.permissions

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isFrom
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isOk
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo1EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo2EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo3EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyContainer
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.EndpointMatch
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension

internal class MetricsPerIncomingEndpointTest {

    companion object {

        private val ecProperties = mapOf(
            "envoy-control.envoy.snapshot.incoming-permissions.enabled" to true,
            "envoy-control.envoy.snapshot.incoming-permissions.metricsPerEndpointEnabled" to true,
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
        """.trimIndent())
        // language=yaml
        private val echo2EnvoyConfig = Echo2EnvoyAuthConfig.copy(configOverride = """
            node:
              metadata:
                proxy_settings:
                  incoming:
                    endpoints:
                      - path: "/secured_endpoint"
                        methods: ["GET"]
                        clients: ["echo"]
                        unlistedClientsPolicy: "log"
                      - path: "/secured_endpoint"
                        clients: ["echo"]
        """.trimIndent())

        // language=yaml
        private val echo3EnvoyConfig = Echo3EnvoyAuthConfig.copy(configOverride = """
            node:
              metadata:
                proxy_settings:
                  outgoing:
                    dependencies:
                      - service: "echo2"
        """.trimIndent())

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
        val echo1Envoy = EnvoyExtension(envoyControl, service1, echo1EnvoyConfig)

        @JvmField
        @RegisterExtension
        val echo2Envoy = EnvoyExtension(envoyControl, service2, echo2EnvoyConfig)

        @JvmField
        @RegisterExtension
        val echo3Envoy = EnvoyExtension(envoyControl, service2, echo3EnvoyConfig)

        private fun registerEcho2WithEnvoyOnIngress() {
            consul.server.operations.registerService(
                    id = "echo2",
                    name = "echo2",
                    address = echo2Envoy.container.ipAddress(),
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
    fun `should produce stats per incoming endpoint`() {
        untilAsserted {
            // when
            val getRequest = echo1Envoy.egressOperations.callService("echo2", pathAndQuery = "/secured_endpoint")

            // then
            val getRequestCount = echo2Envoy.container.admin().statValue("vhost.secured_local_service.vcluster./secured_endpoint_GET_client_echo.upstream_rq_200")?.toInt()
            assertThat(getRequestCount).isGreaterThan(0)
            assertThat(getRequest).isOk().isFrom(service2)

            // when
            val notListedMethodRequest = echo1Envoy.egressOperations.callService("echo2", pathAndQuery = "/secured_endpoint", method = "POST")
            // then
            val notListedMethodRequestCount = echo2Envoy.container.admin().statValue("vhost.secured_local_service.vcluster./secured_endpoint_ALL_HTTP_METHODS_client_echo.upstream_rq_200")?.toInt()
            assertThat(notListedMethodRequestCount).isGreaterThan(0)
            assertThat(notListedMethodRequest).isOk().isFrom(service2)

            // when
            val notListedClientRequest = echo3Envoy.egressOperations.callService("echo2", pathAndQuery = "/secured_endpoint")
            // then
            val notListedClientRequestCount = echo2Envoy.container.admin().statValue("vhost.secured_local_service.vcluster./secured_endpoint_GET_other_clients.upstream_rq_200")?.toInt()
            assertThat(notListedClientRequestCount).isGreaterThan(0)
            assertThat(notListedClientRequest).isOk().isFrom(service2)
        }
    }
}
