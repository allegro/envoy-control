package pl.allegro.tech.servicemesh.envoycontrol.permissions

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import pl.allegro.tech.servicemesh.envoycontrol.assertions.hasNoRBACDenials
import pl.allegro.tech.servicemesh.envoycontrol.assertions.hasOneAccessDenialWithActionBlock
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isRbacAccessLog
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo1EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo2EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration
import pl.allegro.tech.servicemesh.envoycontrol.config.containers.ToxiproxyContainer
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyContainer
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlRunnerTestApp

class IncomingPermissionsEndpointsTest : EnvoyControlTestConfiguration() {

    companion object {
        private const val prefix = "envoy-control.envoy.snapshot"
        private val properties = { sourceClientIp: String ->
            mapOf(
                "$prefix.incoming-permissions.enabled" to true,
                "$prefix.incoming-permissions.source-ip-authentication.ip-from-range.source-ip-client" to
                    "$sourceClientIp/32",
                "$prefix.routes.status.create-virtual-cluster" to true
            )
        }

        // language=yaml
        private fun proxySettings(unlistedEndpointsPolicy: String) = """
            node:
              metadata:
                proxy_settings:
                  incoming:
                    unlistedEndpointsPolicy: $unlistedEndpointsPolicy
                    endpoints:
                    - path: "/path"
                      clients: ["echo2"]
                    - pathPrefix: "/prefix"
                      clients: ["echo2"]
                    - pathRegex: "/regex/.*/segment"
                      clients: ["echo2"]
                    roles: []
                  outgoing:
                    dependencies:
                      - service: "echo"
                      - service: "echo2"
        """.trimIndent()

        private val echoConfig = Echo1EnvoyAuthConfig.copy(
            configOverride = proxySettings(unlistedEndpointsPolicy = "blockAndLog"))

        private val echo2Config = Echo2EnvoyAuthConfig.copy(
            configOverride = proxySettings(unlistedEndpointsPolicy = "blockAndLog"))

        private val echoEnvoy by lazy { envoyContainer1 }
        private val echo2Envoy by lazy { envoyContainer2 }

        @Container
        private val sourceIpClient = ToxiproxyContainer(exposedPortsCount = 2).withNetwork(network)
        private val echoLocalService by lazy { localServiceContainer }

        @JvmStatic
        @BeforeAll
        fun setupTest() {
            setup(appFactoryForEc1 = { consulPort ->
                EnvoyControlRunnerTestApp(properties = properties(sourceIpClient.ipAddress()), consulPort = consulPort)
            },
                envoys = 2,
                envoyConfig = echoConfig,
                secondEnvoyConfig = echo2Config
            )

            registerServiceWithEnvoyOnIngress(name = "echo", envoy = echoEnvoy, tags = listOf("mtls:enabled"))
            registerServiceWithEnvoyOnIngress(name = "echo2", envoy = echo2Envoy, tags = listOf("mtls:enabled"))

            waitForEnvoysInitialized()
        }

        private fun waitForEnvoysInitialized() {
            untilAsserted {
                assertThat(echoEnvoy.admin().isEndpointHealthy("echo2", echo2Envoy.ipAddress())).isTrue()
                assertThat(echo2Envoy.admin().isEndpointHealthy("echo", echoEnvoy.ipAddress())).isTrue()
            }
        }
    }

    @Test
    fun `echo should allow echo2 to access 'path' endpoint`() {
        // when
        val echoResponse = call(service = "echo", from = echo2Envoy, path = "/path")

        // then
        assertThat(echoResponse).isOk().isFrom(echoLocalService)
        assertThat(echoEnvoy.ingressSslRequests).isOne()
        assertThat(echoEnvoy).hasNoRBACDenials()
    }

    @Test
    fun `echo should NOT allow echo2 to access 'path' endpoint with following segments`() {
        // when
        val echoResponse = call(service = "echo", from = echo2Envoy, path = "/path/sub-segment")

        // then
        assertThat(echoResponse).isForbidden()
        assertThat(echoEnvoy.ingressSslRequests).isOne()
        assertThat(echoEnvoy).hasOneAccessDenialWithActionBlock(
            protocol = "https",
            path = "/path/sub-segment",
            method = "GET",
            clientName = "echo2",
            clientIp = echo2Envoy.ipAddress()
        )
    }

    @Test
    fun `echo should allow echo2 to access 'prefix' endpoint`() {
        // when
        val echoResponseOne = call(service = "echo", from = echo2Envoy, path = "/prefix")
        val echoResponseTwo = call(service = "echo", from = echo2Envoy, path = "/prefixes")
        val echoResponseThree = call(service = "echo", from = echo2Envoy, path = "/prefix/segment")

        // then
        assertThat(echoResponseOne).isOk().isFrom(echoLocalService)
        assertThat(echoResponseTwo).isOk().isFrom(echoLocalService)
        assertThat(echoResponseThree).isOk().isFrom(echoLocalService)
        assertThat(echoEnvoy.ingressSslRequests).isEqualTo(3)
        assertThat(echoEnvoy).hasNoRBACDenials()
    }

    @Test
    fun `echo should allow echo2 to access 'regex' endpoint`() {
        // when
        val echoResponseOne = call(service = "echo", from = echo2Envoy, path = "/regex/1/segment")
        val echoResponseTwo = call(service = "echo", from = echo2Envoy, path = "/regex/param-1/segment")

        // then
        assertThat(echoResponseOne).isOk().isFrom(echoLocalService)
        assertThat(echoResponseTwo).isOk().isFrom(echoLocalService)
        assertThat(echoEnvoy.ingressSslRequests).isEqualTo(2)
        assertThat(echoEnvoy).hasNoRBACDenials()
    }

    @Test
    fun `echo should allow echo2 to access 'regex' endpoint without valid segments`() {
        // when
        val echoResponseOne = call(service = "echo", from = echo2Envoy, path = "/regex/1")
        val echoResponseTwo = call(service = "echo", from = echo2Envoy, path = "/regex/param/seg")
        val echoResponseThree = call(service = "echo", from = echo2Envoy, path = "/regex/param/segment/last-segment")

        // then
        assertThat(echoResponseOne).isForbidden()
        assertThat(echoResponseTwo).isForbidden()
        assertThat(echoResponseThree).isForbidden()
        assertThat(echoEnvoy.ingressSslRequests).isEqualTo(3)
    }

    @BeforeEach
    fun startRecordingRBACLogs() {
        echoEnvoy.recordRBACLogs()
        echo2Envoy.recordRBACLogs()
    }

    fun stopRecordingRBACLogs() {
        echoEnvoy.logRecorder.stopRecording()
        echo2Envoy.logRecorder.stopRecording()
    }

    @AfterEach
    override fun cleanupTest() {
        listOf(echoEnvoy, echo2Envoy).forEach { it.admin().resetCounters() }
        stopRecordingRBACLogs()
    }

    private val EnvoyContainer.ingressSslRequests: Int?
        get() = this.admin().statValue("http.ingress_https.downstream_rq_completed")?.toInt()

    private fun EnvoyContainer.recordRBACLogs() {
        logRecorder.recordLogs(::isRbacAccessLog)
    }
}
