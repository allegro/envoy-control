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
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.EndpointMatch

@SuppressWarnings("LargeClass")
internal class IncomingPermissionsParamsInEndpointPathTest : EnvoyControlTestConfiguration() {

    companion object {
        private const val prefix = "envoy-control.envoy.snapshot"
        private val properties = { sourceClientIp: String ->
            mapOf(
                "$prefix.incoming-permissions.enabled" to true,
                "$prefix.incoming-permissions.source-ip-authentication.ip-from-range.source-ip-client" to
                    "$sourceClientIp/32",
                "$prefix.routes.status.create-virtual-cluster" to true,
                "$prefix.routes.status.endpoints" to mutableListOf(EndpointMatch().also { it.path = "/status/" }),
                "$prefix.routes.status.enabled" to true
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
                    - path: "/example-endpoint/{param}/action1"
                      clients: ["authorized-clients"]
                      unlistedClientsPolicy: blockAndLog
                    - path: "/example-endpoint/{param}/action2"
                      clients: ["authorized-clients"]
                      unlistedClientsPolicy: blockAndLog
                    - pathPrefix: "/example-endpoint/{param}/action3"
                      clients: ["authorized-clients"]
                    roles:
                    - name: authorized-clients
                      clients: ["echo3", "source-ip-client"]
                  outgoing:
                    dependencies:
                      - service: "echo"
                      - service: "echo2"
        """.trimIndent()

        private val echoConfig = Echo1EnvoyAuthConfig.copy(
            configOverride = proxySettings(unlistedEndpointsPolicy = "blockAndLog"))

        private val echo2Config = Echo2EnvoyAuthConfig.copy(
            configOverride = proxySettings(unlistedEndpointsPolicy = "log"))

        // language=yaml
        private val echo3Config = """
            node:
              metadata:
                proxy_settings:
                  outgoing:
                    dependencies:
                      - service: "echo"
                      - service: "echo2"
        """.trimIndent()

        private val echoEnvoy by lazy { envoyContainer1 }
        private val echo2Envoy by lazy { envoyContainer2 }
        private lateinit var echo3Envoy: EnvoyContainer

        @Container
        private val sourceIpClient = ToxiproxyContainer(exposedPortsCount = 2).withNetwork(network)
        private val sourceIpClientToEchoProxy by lazy { sourceIpClient.createProxyToEnvoyIngress(envoy = echoEnvoy) }
        private val sourceIpClientToEcho2Proxy by lazy { sourceIpClient.createProxyToEnvoyIngress(envoy = echo2Envoy) }

        private val echoLocalService by lazy { localServiceContainer }
        private val echo2LocalService by lazy { echoContainer2 }

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
            echo3Envoy = createEnvoyContainerWithEcho3Certificate(configOverride = echo3Config)
            echo3Envoy.start()

            registerServiceWithEnvoyOnIngress(name = "echo", envoy = echoEnvoy, tags = listOf("mtls:enabled"))
            registerServiceWithEnvoyOnIngress(name = "echo2", envoy = echo2Envoy, tags = listOf("mtls:enabled"))

            waitForEnvoysInitialized()
        }

        private fun waitForEnvoysInitialized() {
            untilAsserted {
                assertThat(echo3Envoy.admin().isEndpointHealthy("echo", echoEnvoy.ipAddress())).isTrue()
                assertThat(echo3Envoy.admin().isEndpointHealthy("echo2", echo2Envoy.ipAddress())).isTrue()
                assertThat(echoEnvoy.admin().isEndpointHealthy("echo2", echo2Envoy.ipAddress())).isTrue()
                assertThat(echo2Envoy.admin().isEndpointHealthy("echo", echoEnvoy.ipAddress())).isTrue()
            }
        }
    }

    @Test
    fun `echo should allow echo3 to access 'example-endpoint' on exact path with param`() {
        // when
        val echoResponse = call(service = "echo", from = echo3Envoy, path = "/example-endpoint/1/action1")

        // then
        assertThat(echoResponse).isOk().isFrom(echoLocalService)
        assertThat(echoEnvoy.ingressSslRequests).isOne()
        assertThat(echoEnvoy).hasNoRBACDenials()
    }

    @Test
    fun `echo should allow echo2 to access 'example-endpoint' on exact path with param`() {
        // when
        val echoResponse = call(service = "echo", from = echo2Envoy, path = "/example-endpoint/1/action1")

        // then
        assertThat(echoResponse).isForbidden()
        assertThat(echoEnvoy.ingressSslRequests).isOne()
        assertThat(echoEnvoy).hasOneAccessDenialWithActionBlock(
            protocol = "https",
            path = "/example-endpoint/{param}/action1",
            method = "GET",
            clientName = "echo2",
            clientIp = echo2Envoy.ipAddress()
        )
    }

    @Test
    fun `echo should NOT allow echo3 to access 'example-endpoint' on extended path with param`() {
        // when
        val echoResponse = call(service = "echo", from = echo2Envoy, path = "/example-endpoint/1/action1/sub-action")

        // then
        assertThat(echoResponse).isForbidden()
        assertThat(echoEnvoy.ingressSslRequests).isOne()
        assertThat(echoEnvoy).hasOneAccessDenialWithActionBlock(
            protocol = "https",
            path = "/example-endpoint/{param}/action1",
            method = "GET",
            clientName = "echo3",
            clientIp = echo2Envoy.ipAddress()
        )
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
        listOf(echoEnvoy, echo2Envoy, echo3Envoy).forEach { it.admin().resetCounters() }
        stopRecordingRBACLogs()
    }

    private val EnvoyContainer.ingressSslRequests: Int?
        get() = this.admin().statValue("http.ingress_https.downstream_rq_completed")?.toInt()

    private fun EnvoyContainer.recordRBACLogs() {
        logRecorder.recordLogs(::isRbacAccessLog)
    }
}
