package pl.allegro.tech.servicemesh.envoycontrol.permissions

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.hasOneAccessDenialWithActionLog
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isOk
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isRbacAccessLog
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isUnreachable
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo1EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension

class IncomingPermissionsRbacActionTest {

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
                      - service: "failing-echo"
        """.trimIndent()

        private val echoConfig = Echo1EnvoyAuthConfig.copy(configOverride = echoYaml)
        private val failingEchoConfig = Echo1EnvoyAuthConfig.copy(configOverride = echoYaml, serviceName = "failing-echo")

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
        val failingEchoEnvoy = EnvoyExtension(envoyControl, config = failingEchoConfig)
    }

    @BeforeEach
    fun beforeEach() {
        consul.server.operations.registerServiceWithEnvoyOnIngress(echoEnvoy, name = "echo")
        consul.server.operations.registerServiceWithEnvoyOnIngress(failingEchoEnvoy, name = "failing-echo")
        echoEnvoy.container.logRecorder.recordLogs(::isRbacAccessLog)
        failingEchoEnvoy.container.logRecorder.recordLogs(::isRbacAccessLog)
        echoEnvoy.waitForAvailableEndpoints("echo", "failing-echo")
    }

    @AfterEach
    fun afterEach() {
        echoEnvoy.container.logRecorder.stopRecording()
    }

    @Test
    fun `incoming permissions logs should contain rbacAction`() {
        // when
        val response = echoEnvoy.egressOperations.callService(service = "echo", pathAndQuery = "/path")

        // then
        assertThat(response).isOk()
        assertThat(echoEnvoy.container).hasOneAccessDenialWithActionLog(
            protocol = "http",
            rbacAction = "shadow_denied"
        )
    }

    @Test
    fun `rbacAction should be set to shadow_denied when shadow_engine result is denied and upstream is unavailable`() {
        // when
        val response = echoEnvoy.egressOperations.callService(service = "failing-echo", pathAndQuery = "/path")

        // then
        assertThat(response).isUnreachable()
        assertThat(failingEchoEnvoy.container).hasOneAccessDenialWithActionLog(
            protocol = "http",
            rbacAction = "shadow_denied",
            statusCode = "503"
        )
    }
}
