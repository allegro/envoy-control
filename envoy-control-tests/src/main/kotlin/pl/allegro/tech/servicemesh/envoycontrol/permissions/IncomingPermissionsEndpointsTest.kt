package pl.allegro.tech.servicemesh.envoycontrol.permissions

import org.assertj.core.api.Assertions.assertThat
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

class IncomingPermissionsEndpointsTest {

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
                  incoming:
                    unlistedEndpointsPolicy: blockAndLog
                    endpoints: []
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
        val envoy = EnvoyExtension(envoyControl, config = echoConfig)

        @JvmField
        @RegisterExtension
        val secondEnvoy = EnvoyExtension(envoyControl, config = echo2Config)
    }

    @Test
    fun `echo should allow echo2 to access 'path' endpoint on exact path`() {
        // when
        consul.server.operations.registerServiceWithEnvoyOnIngress(envoy, name = "echo", tags = listOf("mtls:enabled"))
        consul.server.operations.registerServiceWithEnvoyOnIngress(secondEnvoy, name = "echo2", tags = listOf("mtls:enabled"))

        // then
        untilAsserted {
            secondEnvoy.egressOperations.callService(service = "echo", pathAndQuery = "/path").also {
                assertThat(it).isOk()
            }
            secondEnvoy.egressOperations.callService(service = "echo", pathAndQuery = "/path/segment").also {
                assertThat(it).isForbidden()
            }
        }
    }

    @Test
    fun `echo should allow echo2 to access 'prefix' endpoint on correct prefix path`() {
        // when
        consul.server.operations.registerServiceWithEnvoyOnIngress(envoy, name = "echo", tags = listOf("mtls:enabled"))
        consul.server.operations.registerServiceWithEnvoyOnIngress(secondEnvoy, name = "echo2", tags = listOf("mtls:enabled"))

        // then
        untilAsserted {
            secondEnvoy.egressOperations.callService(service = "echo", pathAndQuery = "/prefix").also {
                assertThat(it).isOk()
            }
            secondEnvoy.egressOperations.callService(service = "echo", pathAndQuery = "/prefixes").also {
                assertThat(it).isOk()
            }
            secondEnvoy.egressOperations.callService(service = "echo", pathAndQuery = "/prefix/segment").also {
                assertThat(it).isOk()
            }
            secondEnvoy.egressOperations.callService(service = "echo", pathAndQuery = "/wrong-prefix").also {
                assertThat(it).isForbidden()
            }
        }
    }

    @Test
    fun `echo should allow echo2 to access 'regex' endpoint on correct regex path`() {
        // when
        consul.server.operations.registerServiceWithEnvoyOnIngress(envoy, name = "echo", tags = listOf("mtls:enabled"))
        consul.server.operations.registerServiceWithEnvoyOnIngress(secondEnvoy, name = "echo2", tags = listOf("mtls:enabled"))

        // then
        untilAsserted {
            secondEnvoy.egressOperations.callService(service = "echo", pathAndQuery = "/regex/1/segment").also {
                assertThat(it).isOk()
            }
            secondEnvoy.egressOperations.callService(service = "echo", pathAndQuery = "/regex/param-1/segment").also {
                assertThat(it).isOk()
            }
            secondEnvoy.egressOperations.callService(service = "echo", pathAndQuery = "/regex/1").also {
                assertThat(it).isForbidden()
            }
            secondEnvoy.egressOperations.callService(service = "echo", pathAndQuery = "/regex/param/seg").also {
                assertThat(it).isForbidden()
            }
            secondEnvoy.egressOperations.callService(service = "echo", pathAndQuery = "/regex/param/bad-segment").also {
                assertThat(it).isForbidden()
            }
            secondEnvoy.egressOperations.callService(service = "echo", pathAndQuery = "/regex/param/segment/last-segment").also {
                assertThat(it).isForbidden()
            }
        }
    }
}
