package pl.allegro.tech.servicemesh.envoycontrol.permissions

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.hasOneAccessDenialWithActionLog
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isFrom
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isOk
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo1EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension

internal class IncomingPermissionsEmptyEndpointsTest {
    companion object {

        // language=yaml
        private val echoConfig = Echo1EnvoyAuthConfig.copy(
            configOverride = """
            node:
              metadata:
                proxy_settings:
                  incoming:
                    unlistedEndpointsPolicy: log
                    endpoints: []
        """.trimIndent()
        )

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
        val echo = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val envoy = EnvoyExtension(envoyControl, localService = echo, config = echoConfig)
    }

    @Test
    fun `echo should allow any client to access any endpoint and log request`() {
        // when
        val echoResponse = envoy.ingressOperations.callLocalService("/some-endpoint")

        // then
        assertThat(echoResponse).isOk().isFrom(echo)
        assertThat(envoy.container).hasOneAccessDenialWithActionLog(
            protocol = "http",
            rule = "ALLOW_LOGGED_POLICY",
            path = "/some-endpoint",
            method = "GET",
            clientName = "",
            clientIp = envoy.container.gatewayIp()
        )
    }

    @BeforeEach
    fun startRecordingRBACLogs() {
        envoy.recordRBACLogs()
    }

    @AfterEach
    fun cleanupTest() {
        envoy.container.admin().resetCounters()
        envoy.container.logRecorder.stopRecording()
    }
}
