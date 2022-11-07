package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isForbidden
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isOk
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo1EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo2EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.GatewayEnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulMultiClusterExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlClusteredExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension
import pl.allegro.tech.servicemesh.envoycontrol.groups.Mode
import java.time.Duration

class IngressGatewayTest {
    companion object {

        private val pollingInterval = Duration.ofSeconds(1)
        private val stateSampleDuration = Duration.ofSeconds(1)

        @JvmField
        @RegisterExtension
        val consulClusters = ConsulMultiClusterExtension()

        val properties = mapOf(
            "envoy-control.envoy.snapshot.stateSampleDuration" to stateSampleDuration,
            "envoy-control.envoy.snapshot.incoming-permissions.overlapping-paths-fix" to true,
            "envoy-control.envoy.snapshot.incoming-permissions.tls-authentication.services-allowed-to-use-wildcard" to listOf(
                "echo2"
            ),
            "envoy-control.envoy.snapshot.incoming-permissions.enabled" to true,
            "envoy-control.sync.enabled" to true,
            "envoy-control.sync.polling-interval" to pollingInterval.seconds,
            "envoy-control.envoy.snapshot.outgoing-permissions.services-allowed-to-use-wildcard" to setOf(
                "echo2",
                "gateway"
            )
        )

        @JvmField
        @RegisterExtension
        val envoyControlDc1 = EnvoyControlClusteredExtension(
            consulClusters.serverFirst, { properties }, listOf(
                consulClusters
            )
        )

        @JvmField
        @RegisterExtension
        val envoyControlDc2 = EnvoyControlClusteredExtension(
            consulClusters.serverSecond, { properties }, listOf(
                consulClusters
            )
        )

        @JvmField
        @RegisterExtension
        val service1 = EchoServiceExtension()

        // language=yaml
        private val echo1Yaml = """
            node:
              metadata:
                proxy_settings:
                  outgoing:
                    dependencies:
                      - service: "echo2"
                      - service: "gateway"
                  incoming:
                    unlistedEndpointsPolicy: log
                    endpoints: 
                      - path: "/secured_endpoint"
                        clients: ["echo2"]
        """.trimIndent()

        @JvmField
        @RegisterExtension
        val envoy1 = EnvoyExtension(
            envoyControlDc1,
            service1,
            config = Echo1EnvoyAuthConfig.copy(configOverride = echo1Yaml, serviceName = "echo1")
        )

        @JvmField
        @RegisterExtension
        val service2 = EchoServiceExtension()

        // language=yaml
        private val echo2Yaml = """
            node:
              metadata:
                proxy_settings:
                  outgoing:
                    dependencies:
                      - service: "echo1"
                  incoming:
                      unlistedEndpointsPolicy: log
                      endpoints: 
                        - path: "/secured_endpoint"
                          clients: ["*"]
                        - path: "/secured_endpoint_not_echo1"
                          clients: ["echo4"]
        """.trimIndent()

        @JvmField
        @RegisterExtension
        val envoy2 = EnvoyExtension(
            envoyControlDc2,
            service2,
            config = Echo2EnvoyAuthConfig.copy(configOverride = echo2Yaml, serviceName = "echo2")
        )

        // language=yaml
        private val gatewayYaml = """
            node:
              metadata:
                proxy_settings:
                  outgoing:
                    dependencies:
                      - service: "*"
        """.trimIndent()

        @JvmField
        @RegisterExtension
        val gatewayEnvoy = EnvoyExtension(
            envoyControlDc2,
            config = GatewayEnvoyAuthConfig.copy(configOverride = gatewayYaml, serviceName = "gateway"),
            mode = Mode.INGRESS_GATEWAY
        )
    }

    private val adminEnvoy1 = envoy1.container.admin()
    private val adminEnvoy2 = envoy2.container.admin()
    private val adminGateway = gatewayEnvoy.container.admin()

    fun init() {
        consulClusters.serverFirst.operations.registerServiceWithEnvoyOnIngress(
            envoy1,
            name = "echo1",
            tags = listOf("mtls:enabled", "envoy")
        )
        consulClusters.serverSecond.operations.registerServiceWithEnvoyOnIngress(
            envoy2,
            name = "echo2",
            tags = listOf("mtls:enabled", "envoy")
        )
        consulClusters.serverSecond.operations.registerServiceWithEnvoyOnIngress(gatewayEnvoy, name = "gateway")
        envoy1.waitForAvailableEndpoints("echo2")
        gatewayEnvoy.waitForAvailableEndpoints("echo2")
    }

    @Test
    fun `dc ingress gateway should pass traffic to correct upstream and make tls termination on final destination`() {
        init()
        val response = envoy1.egressOperations.callService("echo2", pathAndQuery = "/secured_endpoint")
        assertThat(response).isOk()

        untilAsserted(wait = Duration.ofSeconds(15)) {
            assertThat(adminEnvoy1.statValue("cluster.echo2.upstream_cx_http2_total")?.toInt()).isEqualTo(1)
            assertThat(adminEnvoy1.statValue("cluster.echo2.ssl.handshake")?.toInt()).isEqualTo(1)
            assertThat(adminEnvoy1.statValue("cluster.echo2.ssl.fail_verify_error")?.toInt()).isEqualTo(0)
            assertThat(adminEnvoy1.statValue("cluster.echo2.upstream_rq_2xx")?.toInt()).isEqualTo(1)

            assertThat(adminEnvoy2.statValue("http.ingress_https.downstream_cx_ssl_total")?.toInt()).isEqualTo(1)
            assertThat(adminEnvoy2.statValue("http.ingress_https.downstream_rq_2xx")?.toInt()).isEqualTo(1)
            assertThat(adminEnvoy2.statValue("http.ingress_https.rbac.allowed")?.toInt()).isEqualTo(1)

            assertThat(adminGateway.statValue("tcp.echo2_tcp.downstream_cx_total")?.toInt()).isEqualTo(1)
            assertThat(adminGateway.statValue("cluster.echo2.upstream_cx_active")?.toInt()).isEqualTo(1)
        }
    }

    @Test
    fun `dc ingress gateway should pass traffic to correct upstream and deny on rbac for unlisted client`() {
        init()

        val response = envoy1.egressOperations.callService("echo2", pathAndQuery = "/secured_endpoint_not_echo1")
        assertThat(response).isForbidden()

        untilAsserted(wait = Duration.ofSeconds(15)) {
            assertThat(adminEnvoy1.statValue("cluster.echo2.upstream_cx_http2_total")?.toInt()).isEqualTo(1)
            assertThat(adminEnvoy1.statValue("cluster.echo2.ssl.handshake")?.toInt()).isEqualTo(1)
            assertThat(adminEnvoy1.statValue("cluster.echo2.ssl.fail_verify_error")?.toInt()).isEqualTo(0)
            assertThat(adminEnvoy1.statValue("cluster.echo2.upstream_rq_403")?.toInt()).isEqualTo(1)

            assertThat(adminEnvoy2.statValue("http.ingress_https.downstream_cx_ssl_total")?.toInt()).isEqualTo(1)
            assertThat(adminEnvoy2.statValue("http.ingress_https.downstream_rq_4xx")?.toInt()).isEqualTo(1)
            assertThat(adminEnvoy2.statValue("http.ingress_https.rbac.denied")?.toInt()).isEqualTo(1)

            assertThat(adminGateway.statValue("tcp.echo2_tcp.downstream_cx_total")?.toInt()).isEqualTo(1)
            assertThat(adminGateway.statValue("cluster.echo2.upstream_cx_active")?.toInt()).isEqualTo(1)
        }
    }
}
