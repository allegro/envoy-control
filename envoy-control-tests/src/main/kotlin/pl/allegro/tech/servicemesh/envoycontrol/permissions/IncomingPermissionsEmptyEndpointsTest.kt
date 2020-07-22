package pl.allegro.tech.servicemesh.envoycontrol.permissions

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.asssertions.hasOneAccessDenialWithActionLog
import pl.allegro.tech.servicemesh.envoycontrol.asssertions.isRbacAccessLog
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo1EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlRunnerTestApp
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyContainer

internal class IncomingPermissionsEmptyEndpointsTest : EnvoyControlTestConfiguration() {
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
                    endpoints: []
        """.trimIndent())

        private val echoEnvoy by lazy { envoyContainer1 }
        private val echoLocalService by lazy { localServiceContainer }

        @JvmStatic
        @BeforeAll
        fun setupTest() {
            setup(appFactoryForEc1 = { consulPort ->
                EnvoyControlRunnerTestApp(properties = properties, consulPort = consulPort) },
                envoyConfig = echoConfig
            )
            waitForEnvoysInitialized()
        }

        private fun waitForEnvoysInitialized() {
            untilAsserted {
                assertThat(echoEnvoy.admin().statValue("http.ingress_http.rq_total")).isNotEqualTo("-1")
            }
        }
    }

    @Test
    fun `echo should allow any client to access any endpoint and log request`() {
        // when
        val echoResponse = callEnvoyIngress(envoy = echoEnvoy, path = "/some-endpoint")

        // then
        assertThat(echoResponse).isOk().isFrom(echoLocalService)
        assertThat(echoEnvoy).hasOneAccessDenialWithActionLog(
                protocol = "http",
                path = "/some-endpoint",
                method = "GET",
                clientName = "",
                clientIp = echoEnvoy.gatewayIp()
        )
    }

    @BeforeEach
    fun startRecordingRBACLogs() {
        echoEnvoy.recordRBACLogs()
    }

    @AfterEach
    override fun cleanupTest() {
        echoEnvoy.admin().resetCounters()
        echoEnvoy.logRecorder.stopRecording()
    }

    private fun EnvoyContainer.recordRBACLogs() {
        logRecorder.recordLogs(::isRbacAccessLog)
    }
}
