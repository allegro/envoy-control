package pl.allegro.tech.servicemesh.envoycontrol.permissions

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.hasOneAccessDenialWithActionBlock
import pl.allegro.tech.servicemesh.envoycontrol.assertions.hasOneAccessDenialWithActionLog
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isForbidden
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isFrom
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isOk
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo1EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo2EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension

internal class IncomingPermissionsEmptyClientsTest {
    companion object {

        // language=yaml
        private val echoConfig = Echo1EnvoyAuthConfig.copy(
            configOverride = """
            node:
              metadata:
                proxy_settings:
                  incoming:
                    unlistedEndpointsPolicy: log
                    endpoints: 
                    - path: /blocked-for-all
                      clients: []
        """.trimIndent()
        )

        // language=yaml
        private val echo2Config = Echo2EnvoyAuthConfig.copy(
            configOverride = """
            node:
              metadata:
                proxy_settings:
                  incoming:
                    endpoints: 
                    - path: /logged-for-all
                      clients: []
                      unlistedClientsPolicy: log
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
        val echo2 = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val envoy1 = EnvoyExtension(envoyControl, localService = echo, config = echoConfig)

        @JvmField
        @RegisterExtension
        val envoy2 = EnvoyExtension(envoyControl, localService = echo2, config = echo2Config)
    }

    @Test
    fun `echo should deny clients access to 'blocked-for-all' endpoint`() {
        // when
        val echoResponse = envoy1.ingressOperations.callLocalService("/blocked-for-all")

        // then
        assertThat(echoResponse).isForbidden()
        assertThat(envoy1.container).hasOneAccessDenialWithActionBlock(
            protocol = "http",
            rule = "{\"path\":\"/blocked-for-all\",\"pathMatchingType\":\"PATH\"}",
            path = "/blocked-for-all",
            method = "GET",
            clientName = "",
            trustedClient = false,
            authority = envoy1.container.ingressHost(),
            clientIp = envoy1.container.gatewayIp()
        )
    }

    @Test
    fun `echo should allow clients access to 'unlisted' endpoint and log it`() {
        // when
        val echoResponse = envoy1.ingressOperations.callLocalService("/unlisted")

        // then
        assertThat(echoResponse).isOk().isFrom(echo)
        assertThat(envoy1.container).hasOneAccessDenialWithActionLog(
            protocol = "http",
            rule = "ALLOW_UNLISTED_POLICY",
            path = "/unlisted",
            method = "GET",
            clientName = "",
            clientIp = envoy1.container.gatewayIp()
        )
    }

    @Test
    fun `echo2 should allow clients access to 'logged-for-all' endpoint and log it`() {
        // when
        val echo2Response = envoy2.ingressOperations.callLocalService("/logged-for-all")

        // then
        assertThat(echo2Response).isOk().isFrom(echo2)
        assertThat(envoy2.container).hasOneAccessDenialWithActionLog(
            protocol = "http",
            rule = "{\"path\":\"/logged-for-all\",\"pathMatchingType\":\"PATH\", \"unlistedClientsPolicy\":\"LOG\"}",
            path = "/logged-for-all",
            method = "GET",
            clientName = "",
            clientIp = envoy2.container.gatewayIp()
        )
    }

    @Test
    fun `echo2 should deny clients access to 'unlisted' endpoint`() {
        // when
        val echo2Response = envoy2.ingressOperations.callLocalService("/unlisted")

        // then
        assertThat(echo2Response).isForbidden()
        assertThat(envoy2.container).hasOneAccessDenialWithActionBlock(
            protocol = "http",
            rule = "?",
            path = "/unlisted",
            method = "GET",
            clientName = "",
            trustedClient = false,
            authority = envoy2.container.ingressHost(),
            clientIp = envoy2.container.gatewayIp()
        )
    }

    @BeforeEach
    fun startRecordingRBACLogs() {
        listOf(envoy1, envoy2).forEach { it.recordRBACLogs() }
    }

    @AfterEach
    fun cleanupTest() {
        listOf(envoy1, envoy2).forEach {
            it.container.admin().resetCounters()
            it.container.logRecorder.stopRecording()
        }
    }
}
