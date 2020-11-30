package pl.allegro.tech.servicemesh.envoycontrol.permissions

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.hasOneAccessDenialWithActionLog
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isOk
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isRbacAccessLog
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo1EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension

class IncomingPermissionsRequestIdTest {

    companion object {

        // language=yaml
        private val echoYaml = """
            node:
              metadata:
                proxy_settings:
                  incoming:
                    endpoints:
                    - path: "/path"
                      unlistedClientsPolicy: log
                      clients: []
                  outgoing:
                    dependencies:
                      - service: "echo"
        """.trimIndent()

        private val echoConfig = Echo1EnvoyAuthConfig.copy(configOverride = echoYaml)

        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(consul, mapOf(
            "envoy-control.envoy.snapshot.incoming-permissions.enabled" to true,
            "envoy-control.envoy.snapshot.incoming-permissions.request-identification-headers" to listOf("x-request-id")
        ))

        @JvmField
        @RegisterExtension
        val echoService = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val echoEnvoy = EnvoyExtension(envoyControl, config = echoConfig, localService = echoService)
    }

    @Test
    fun `echo should allow echo2 to access 'path' endpoint on exact path`() {
        // given
        echoEnvoy.container.logRecorder.recordLogs(::isRbacAccessLog)
        consul.server.operations.registerServiceWithEnvoyOnIngress(echoEnvoy, name = "echo")

        untilAsserted {
            // when
            val response = echoEnvoy.egressOperations.callService(service = "echo", pathAndQuery = "/path", headers = mapOf(
                "x-request-id" to "123"
            ))

            // then
            assertThat(response).isOk()
            assertThat(echoEnvoy.container).hasOneAccessDenialWithActionLog(
                protocol = "http",
                requestId = "123"
            )
        }
    }
}
