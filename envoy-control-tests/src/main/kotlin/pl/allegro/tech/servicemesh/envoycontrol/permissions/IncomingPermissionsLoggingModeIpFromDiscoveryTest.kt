package pl.allegro.tech.servicemesh.envoycontrol.permissions

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.hasOneAccessDenialWithActionLog
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isFrom
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isOk
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isRbacAccessLog
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo1EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo2EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyContainer
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension

class IncomingPermissionsLoggingModeIpFromDiscoveryTest {
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
                      clients: ["other-client"]
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

        private val prefix = "envoy-control.envoy.snapshot"

        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(
            consul, mapOf(
                "$prefix.incoming-permissions.enabled" to true,
                "$prefix.incoming-permissions.source-ip-authentication.ip-from-service-discovery.enabled-for-incoming-services" to
                    listOf("other-client")
            )
        )

        @JvmField
        @RegisterExtension
        val echoService = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val otherService = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val echoEnvoy = EnvoyExtension(envoyControl, config = echoConfig, localService = echoService)

        @JvmField
        @RegisterExtension
        val echo2Envoy = EnvoyExtension(envoyControl, config = echo2Config)
    }

    @Test
    fun `echo should allow echo2 to access 'path' even though its not defined on the clients list`() {
        echoEnvoy.container.logRecorder.recordLogs(::isRbacAccessLog)
        consul.server.operations.registerService(otherService, name = "other-client")
        consul.server.operations.registerService(
            name = "echo",
            address = echoEnvoy.container.ipAddress(),
            port = EnvoyContainer.INGRESS_LISTENER_CONTAINER_PORT
        )

        untilAsserted {
            assertThat(echo2Envoy.container.admin().isEndpointHealthy("echo", echoEnvoy.container.ipAddress())).isTrue()

            // when
            val response = echo2Envoy.egressOperations.callService(service = "echo", pathAndQuery = "/path")

            // then
            assertThat(response).isOk().isFrom(echoService)
            assertThat(echoEnvoy.container).hasOneAccessDenialWithActionLog(
                protocol = "http",
                path = "/path",
                method = "GET",
                clientName = "echo2",
                clientIp = echo2Envoy.container.ipAddress()
            )
        }
    }
}
