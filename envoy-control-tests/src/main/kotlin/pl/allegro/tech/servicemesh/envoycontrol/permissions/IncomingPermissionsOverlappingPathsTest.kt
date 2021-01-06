package pl.allegro.tech.servicemesh.envoycontrol.permissions

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isOk
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo1EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo2EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension

class IncomingPermissionsOverlappingPathsTest {

    companion object {

        // language=yaml
        private val echoYaml = """
            node:
              metadata:
                proxy_settings:
                  incoming:
                    unlistedEndpointsPolicy: log
                    endpoints:
                      - pathRegex: "/a/b/.+"
                        clients: [ echo3 ]
                        unlistedClientsPolicy: log
                      - pathRegex: "/a/.+"
                        clients: [ echo3 ]
                        unlistedClientsPolicy: blockAndLog
                  outgoing:
                    dependencies: []
        """.trimIndent()

        // language=yaml
        private val echo2Yaml = """
            node:
              metadata:
                proxy_settings:
                  outgoing:
                    dependencies:
                      - service: "echo"
        """.trimIndent()

        private val echoConfig = Echo1EnvoyAuthConfig.copy(configOverride = echoYaml)
        private val echo2Config = Echo2EnvoyAuthConfig.copy(configOverride = echo2Yaml)

        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(
            consul, mapOf(
                "envoy-control.envoy.snapshot.incoming-permissions.enabled" to true
            )
        )

        @JvmField
        @RegisterExtension
        val echoService = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val echoEnvoy = EnvoyExtension(envoyControl, config = echoConfig, localService = echoService)

        @JvmField
        @RegisterExtension
        val echo2Envoy = EnvoyExtension(envoyControl, config = echo2Config)
    }

    @BeforeEach
    fun beforeEach() {
        consul.server.operations.registerServiceWithEnvoyOnIngress(
            echoEnvoy,
            name = "echo",
            tags = listOf("mtls:enabled")
        )
        waitForEnvoysInitialized()
    }

    private fun waitForEnvoysInitialized() {
        untilAsserted {
            assertThat(echo2Envoy.container.admin().isEndpointHealthy("echo", echoEnvoy.container.ipAddress())).isTrue()
        }
    }

    @Test
    fun `echo should allow echo2 to access 'path' endpoint on exact path`() {
        // expect
        val response = echo2Envoy.egressOperations.callService(service = "echo", pathAndQuery = "/a/b/c")
        assertThat(response).isOk()
    }
}
