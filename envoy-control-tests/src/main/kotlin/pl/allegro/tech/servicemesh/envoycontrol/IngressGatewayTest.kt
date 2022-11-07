package pl.allegro.tech.servicemesh.envoycontrol

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isRbacAccessLog
import pl.allegro.tech.servicemesh.envoycontrol.config.Xds
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulMultiClusterExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlClusteredExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension
import pl.allegro.tech.servicemesh.envoycontrol.permissions.IncomingPermissionsRbacActionTest
import java.time.Duration

class IngressGatewayTest {
    companion object {

        val pollingInterval = Duration.ofSeconds(1)
        val stateSampleDuration = Duration.ofSeconds(1)
        val defaultDuration: Duration = Duration.ofSeconds(90)

        @JvmField
        @RegisterExtension
        val consulClusters = ConsulMultiClusterExtension()

        val properties = mapOf(
            "envoy-control.envoy.snapshot.stateSampleDuration" to stateSampleDuration,
            "envoy-control.sync.enabled" to true,
            "envoy-control.sync.polling-interval" to pollingInterval.seconds,
            "envoy-control.envoy.snapshot.outgoing-permissions.services-allowed-to-use-wildcard" to setOf("echo2", "gateway")
        )


        @JvmField
        @RegisterExtension
        val envoyControlDc1 = EnvoyControlClusteredExtension(consulClusters.serverFirst, { properties }, listOf(
            consulClusters
        ))

        @JvmField
        @RegisterExtension
        val envoyControlDc2 = EnvoyControlClusteredExtension(consulClusters.serverSecond, { properties }, listOf(
            consulClusters
        ))

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
                incoming:
                    unlistedEndpointsPolicy: log
                    endpoints: []
        """.trimIndent()

        @JvmField
        @RegisterExtension
        val envoy1 = EnvoyExtension(envoyControlDc1, service1, config = Xds.copy(configOverride = echo1Yaml, serviceName = "echo1"))

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
                    endpoints: []
        """.trimIndent()

        @JvmField
        @RegisterExtension
        val envoy2 = EnvoyExtension(
            envoyControlDc2, service2, config = Xds.copy(configOverride = echo2Yaml, serviceName = "echo2")
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
        val gatewayEnvoy = EnvoyExtension(envoyControlDc2, config = Xds.copy(configOverride = gatewayYaml, serviceName ="gateway"))


    }
    @BeforeEach
    fun beforeEach() {
        consulClusters.serverFirst.operations.registerServiceWithEnvoyOnIngress(envoy1, name = "echo1")
        consulClusters.serverSecond.operations.registerServiceWithEnvoyOnIngress(envoy2, name = "echo2")
        consulClusters.serverSecond.operations.registerServiceWithEnvoyOnIngress(gatewayEnvoy, name = "gateway")
        envoy1.waitForAvailableEndpoints("echo", "gateway")
    }

    @Test
    fun `dc ingress gateway should pass traffic to correct upstream`() {
        envoy1.egressOperations.callService("echo2")

        val adminEnvoy1 = envoy1.container.admin()
        val connections = adminEnvoy1.statValue("cluster.echo.upstream_cx_http2_total")?.toInt()

    }
}
