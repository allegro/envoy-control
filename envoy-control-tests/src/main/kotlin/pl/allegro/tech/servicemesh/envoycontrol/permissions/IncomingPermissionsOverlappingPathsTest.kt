package pl.allegro.tech.servicemesh.envoycontrol.permissions

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isOk
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo1EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo2EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo3EnvoyAuthConfig
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
        private val echo3Yaml = """
            node:
              metadata:
                proxy_settings:
                  incoming:
                    unlistedEndpointsPolicy: blockAndLog
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
                      - service: "echo3"
        """.trimIndent()

        private val echoConfig = Echo1EnvoyAuthConfig.copy(configOverride = echoYaml)
        private val echo2Config = Echo2EnvoyAuthConfig.copy(configOverride = echo2Yaml)
        private val echo3Config = Echo3EnvoyAuthConfig.copy(configOverride = echo3Yaml)

        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(
            consul, mapOf(
                "envoy-control.envoy.snapshot.incoming-permissions.enabled" to true,
                "envoy-control.envoy.snapshot.incoming-permissions.overlapping-paths-fix" to true
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

        @JvmField
        @RegisterExtension
        val echo3Envoy = EnvoyExtension(envoyControl, config = echo3Config, localService = echoService)
    }

    @BeforeEach
    fun beforeEach() {
        consul.server.operations.registerServiceWithEnvoyOnIngress(
            echoEnvoy,
            name = "echo",
            tags = listOf("mtls:enabled")
        )
        consul.server.operations.registerServiceWithEnvoyOnIngress(
            echo3Envoy,
            name = "echo3",
            tags = listOf("mtls:enabled")
        )
        waitForEnvoysInitialized()
    }

    private fun waitForEnvoysInitialized() {
        untilAsserted {
            assertThat(echo2Envoy.container.admin().isEndpointHealthy("echo", echoEnvoy.container.ipAddress())).isTrue()
            assertThat(echo2Envoy.container.admin().isEndpointHealthy("echo3", echo3Envoy.container.ipAddress())).isTrue()
        }
    }

    @Test
    fun `should allow defining endpoints with policy log that are subset of blockAndLog when unlinstedEnpointsPolicy is log`() {
        // expect
        val response = echo2Envoy.egressOperations.callService(service = "echo", pathAndQuery = "/a/b/c")
        assertThat(response).isOk()
    }

    @Test
    fun `should allow defining endpoints with policy log that are subset of blockAndLog when unlinstedEnpointsPolicy is blockAndLog`() {
        // expect
        val response = echo2Envoy.egressOperations.callService(service = "echo3", pathAndQuery = "/a/b/c")
        assertThat(response).isOk()
    }
}
