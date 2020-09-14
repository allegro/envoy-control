package pl.allegro.tech.servicemesh.envoycontrol.permissions

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isForbidden
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isOk
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo1EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo2EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension

class IncomingPermissionsPathMatchingTest {

    companion object {

        // language=yaml
        private val echoYaml = """
            node:
              metadata:
                proxy_settings:
                  incoming:
                    unlistedEndpointsPolicy: blockAndLog
                    endpoints:
                    - path: "/path"
                      clients: ["echo2"]
                    - pathPrefix: "/prefix"
                      clients: ["echo2"]
                    - pathRegex: "/regex/.*/segment"
                      clients: ["echo2"]
                    roles: []
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
        val envoyControl = EnvoyControlExtension(consul, mapOf(
            "envoy-control.envoy.snapshot.incoming-permissions.enabled" to true
        ))

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
        consul.server.operations.registerServiceWithEnvoyOnIngress(echoEnvoy, name = "echo", tags = listOf("mtls:enabled"))
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
        echo2Envoy.egressOperations.callService(service = "echo", pathAndQuery = "/path").also {
            assertThat(it).isOk()
        }
        echo2Envoy.egressOperations.callService(service = "echo", pathAndQuery = "/path/segment").also {
            assertThat(it).isForbidden()
        }
    }

    @Test
    fun `echo should allow echo2 to access 'prefix' endpoint on correct prefix path`() {
        // expect
        echo2Envoy.egressOperations.callService(service = "echo", pathAndQuery = "/prefix").also {
            assertThat(it).isOk()
        }
        echo2Envoy.egressOperations.callService(service = "echo", pathAndQuery = "/prefixes").also {
            assertThat(it).isOk()
        }
        echo2Envoy.egressOperations.callService(service = "echo", pathAndQuery = "/prefix/segment").also {
            assertThat(it).isOk()
        }
        echo2Envoy.egressOperations.callService(service = "echo", pathAndQuery = "/wrong-prefix").also {
            assertThat(it).isForbidden()
        }
    }

    @Test
    fun `echo should allow echo2 to access 'regex' endpoint on correct regex path`() {
        // expect
        echo2Envoy.egressOperations.callService(service = "echo", pathAndQuery = "/regex/1/segment").also {
            assertThat(it).isOk()
        }
        echo2Envoy.egressOperations.callService(service = "echo", pathAndQuery = "/regex/param-1/segment").also {
            assertThat(it).isOk()
        }
        echo2Envoy.egressOperations.callService(service = "echo", pathAndQuery = "/regex/param/param2/segment").also {
            assertThat(it).isOk()
        }
        echo2Envoy.egressOperations.callService(service = "echo", pathAndQuery = "/regex/1").also {
            assertThat(it).isForbidden()
        }
        echo2Envoy.egressOperations.callService(service = "echo", pathAndQuery = "/regex/param/bad-segment").also {
            assertThat(it).isForbidden()
        }
        echo2Envoy.egressOperations.callService(service = "echo", pathAndQuery = "/regex/param/segment/last-segment").also {
            assertThat(it).isForbidden()
        }
    }
}
