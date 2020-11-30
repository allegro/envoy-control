package pl.allegro.tech.servicemesh.envoycontrol.permissions

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.assertions.hasOneAccessDenialWithActionBlock
import pl.allegro.tech.servicemesh.envoycontrol.assertions.hasOneAccessDenialWithActionLog
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isRbacAccessLog
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo1EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo2EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlRunnerTestApp
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyContainer

internal class IncomingPermissionsEmptyClientsTest : EnvoyControlTestConfiguration() {
    companion object {
        private val properties = mapOf(
            "envoy-control.envoy.snapshot.incoming-permissions.enabled" to true
        )

        // language=yaml
        private val echoConfig = Echo1EnvoyAuthConfig.copy(configOverride = """
            node:
              metadata:
                proxy_settings:
                  incoming:
                    unlistedEndpointsPolicy: log
                    endpoints: 
                    - path: /blocked-for-all
                      clients: []
        """.trimIndent())

        // language=yaml
        private val echo2Config = Echo2EnvoyAuthConfig.copy(configOverride = """
            node:
              metadata:
                proxy_settings:
                  incoming:
                    endpoints: 
                    - path: /logged-for-all
                      clients: []
                      unlistedClientsPolicy: log
        """.trimIndent())

        private val echoEnvoy by lazy { envoyContainer1 }
        private val echoLocalService by lazy { localServiceContainer }

        private val echo2Envoy by lazy { envoyContainer2 }
        private val echo2LocalService by lazy { echoContainer2 }

        @JvmStatic
        @BeforeAll
        fun setupTest() {
            setup(appFactoryForEc1 = { consulPort ->
                EnvoyControlRunnerTestApp(properties = properties, consulPort = consulPort) },
                envoys = 2,
                envoyConfig = echoConfig,
                secondEnvoyConfig = echo2Config
            )
            waitForEnvoysInitialized()
        }

        private fun waitForEnvoysInitialized() {
            untilAsserted {
                assertThat(echoEnvoy.admin().isIngressReady()).isTrue()
                assertThat(echo2Envoy.admin().isIngressReady()).isTrue()
            }
        }
    }

    @Test
    fun `echo should deny clients access to 'blocked-for-all' endpoint`() {
        // when
        val echoResponse = callEnvoyIngress(envoy = echoEnvoy, path = "/blocked-for-all")

        // then
        assertThat(echoResponse).isForbidden()
        assertThat(echoEnvoy).hasOneAccessDenialWithActionBlock(
            protocol = "http",
            path = "/blocked-for-all",
            method = "GET",
            clientName = "",
            clientIp = echoEnvoy.gatewayIp()
        )
    }

    @Test
    fun `echo should allow clients access to 'unlisted' endpoint and log it`() {
        // when
        val echoResponse = callEnvoyIngress(envoy = echoEnvoy, path = "/unlisted")

        // then
        assertThat(echoResponse).isOk().isFrom(echoLocalService)
        assertThat(echoEnvoy).hasOneAccessDenialWithActionLog(
            protocol = "http",
            path = "/unlisted",
            method = "GET",
            clientName = "",
            clientIp = echoEnvoy.gatewayIp()
        )
    }

    @Test
    fun `echo2 should allow clients access to 'logged-for-all' endpoint and log it`() {
        // when
        val echo2Response = callEnvoyIngress(envoy = echo2Envoy, path = "/logged-for-all")

        // then
        assertThat(echo2Response).isOk().isFrom(echo2LocalService)
        assertThat(echo2Envoy).hasOneAccessDenialWithActionLog(
            protocol = "http",
            path = "/logged-for-all",
            method = "GET",
            clientName = "",
            clientIp = echo2Envoy.gatewayIp()
        )
    }

    @Test
    fun `echo2 should deny clients access to 'unlisted' endpoint`() {
        // when
        val echo2Response = callEnvoyIngress(envoy = echo2Envoy, path = "/unlisted")

        // then
        assertThat(echo2Response).isForbidden()
        assertThat(echo2Envoy).hasOneAccessDenialWithActionBlock(
            protocol = "http",
            path = "/unlisted",
            method = "GET",
            clientName = "",
            clientIp = echo2Envoy.gatewayIp()
        )
    }

    @BeforeEach
    fun startRecordingRBACLogs() {
        listOf(echoEnvoy, echo2Envoy).forEach { it.recordRBACLogs() }
    }

    @AfterEach
    override fun cleanupTest() {
        listOf(echoEnvoy, echo2Envoy).forEach {
            it.admin().resetCounters()
            it.logRecorder.stopRecording()
        }
    }

    private fun EnvoyContainer.recordRBACLogs() {
        logRecorder.recordLogs(::isRbacAccessLog)
    }
}
