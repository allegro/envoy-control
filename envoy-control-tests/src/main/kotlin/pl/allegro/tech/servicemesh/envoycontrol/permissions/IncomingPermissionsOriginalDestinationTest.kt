package pl.allegro.tech.servicemesh.envoycontrol.permissions

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.hasNoRBACDenials
import pl.allegro.tech.servicemesh.envoycontrol.assertions.hasOneAccessDenialWithActionBlock
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isForbidden
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isFrom
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isOk
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo1EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension

class IncomingPermissionsOriginalDestinationTest {

    companion object {

        // language=yaml
        private val echoYaml = """
            node:
              metadata:
                proxy_settings:
                  incoming:
                    endpoints:
                    - path: "/allowed-echo"
                      unlistedClientsPolicy: blockAndLog
                      clients: [echo, envoy-original-destination]
                    - path: "/blocked-echo"
                      unlistedClientsPolicy: blockAndLog
                      clients: [echo2]
                  outgoing:
                    dependencies:
                      - service: "echo"
                      - service: "echo2"
        """.trimIndent()

        private val echoConfig = Echo1EnvoyAuthConfig.copy(configOverride = echoYaml)
        private val failingEchoConfig =
            Echo1EnvoyAuthConfig.copy(configOverride = echoYaml, serviceName = "failing-echo")

        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()
        private const val prefix = "envoy-control.envoy.snapshot"

        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(
            consul, mapOf(
                "${prefix}.incoming-permissions.enabled" to true,
                "${prefix}.outgoing-permissions.enabled" to true,
                "${prefix}.routes.status.create-virtual-cluster" to true
            )
        )

        @JvmField
        @RegisterExtension
        val echoService = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val echoService2 = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val echoEnvoy = EnvoyExtension(envoyControl, config = echoConfig, localService = echoService)

        @JvmField
        @RegisterExtension
        val echo2Envoy = EnvoyExtension(envoyControl, config = failingEchoConfig, localService = echoService2)
    }

    @BeforeEach
    fun beforeEach() {
        consul.server.operations.registerServiceWithEnvoyOnIngress(
            echoEnvoy,
            name = "echo",
            tags = listOf("mtls:enabled")
        )
        consul.server.operations.registerServiceWithEnvoyOnIngress(
            echo2Envoy,
            name = "echo2",
            tags = listOf("mtls:enabled")
        )
        waitForEnvoysInitialized()
        echoEnvoy.recordRBACLogs()
        echo2Envoy.recordRBACLogs()
    }

    private fun waitForEnvoysInitialized() {
        untilAsserted {
            Assertions.assertThat(
                echoEnvoy.container.admin()
                    .isEndpointHealthy("echo2", echo2Envoy.container.ipAddress())
            ).isTrue()
            Assertions.assertThat(
                echo2Envoy.container.admin().isEndpointHealthy(
                    "echo",
                    echoEnvoy.container.ipAddress()
                )
            ).isTrue()
        }
    }

    @AfterEach
    fun cleanupTest() {
        echoEnvoy.stopRecordingRBAC()
        echo2Envoy.stopRecordingRBAC()
    }

    @Test
    fun `should allow direct request when using orginal destination and echo service is specified as client`() {
        // when
        val response = echo2Envoy.ingressOperations.callServiceWithOriginalDst(
            echoService2, "/allowed-echo", "echo", true
        )

        // then
        Assertions.assertThat(response).isOk().isFrom(echoService2)
        Assertions.assertThat(echo2Envoy.container).hasNoRBACDenials()
    }

    @Test
    fun `should block direct request when using orginal destination and echo service is not specified as client`() {
        // when
        val response = echo2Envoy.ingressOperations.callServiceWithOriginalDst(
            echoService2, "/blocked-echo", "echo"
        )

        // then
        Assertions.assertThat(response).isForbidden()

        Assertions.assertThat(echo2Envoy.container).hasOneAccessDenialWithActionBlock(
            protocol = "https",
            path = "/blocked-echo",
            method = "GET",
            clientName = "echo",
            trustedClient = false,
            clientIp = echoEnvoy.container.ipAddress().replaceAfterLast(".", "1")
        )
    }
}
