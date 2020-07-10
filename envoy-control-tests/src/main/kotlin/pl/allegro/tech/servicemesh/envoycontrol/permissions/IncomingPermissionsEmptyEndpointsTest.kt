package pl.allegro.tech.servicemesh.envoycontrol

import okhttp3.MediaType
import okhttp3.RequestBody
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import pl.allegro.tech.servicemesh.envoycontrol.asssertions.hasNoRBACDenials
import pl.allegro.tech.servicemesh.envoycontrol.asssertions.hasOneAccessDenialWithActionBlock
import pl.allegro.tech.servicemesh.envoycontrol.asssertions.hasOneAccessDenialWithActionLog
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo1EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo2EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlRunnerTestApp
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration
import pl.allegro.tech.servicemesh.envoycontrol.config.containers.ToxiproxyContainer
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyContainer

@SuppressWarnings("LargeClass")
internal class IncomingPermissionEmptyEndpointsTest : EnvoyControlTestConfiguration() {
    companion object {
        private const val prefix = "envoy-control.envoy.snapshot"
        private val properties = { sourceClientIp: String -> mapOf(
            "$prefix.incoming-permissions.enabled" to true,
            "$prefix.incoming-permissions.source-ip-authentication.ip-from-range.source-ip-client" to
                "$sourceClientIp/32",
            "$prefix.routes.status.create-virtual-cluster" to true,
            "$prefix.routes.status.path-prefix" to "/status/",
            "$prefix.routes.status.enabled" to true
        ) }

        // language=yaml
        private fun proxySettings(unlistedEndpointsPolicy: String) = """
            node:
              metadata:
                proxy_settings:
                  incoming:
                    unlistedEndpointsPolicy: $unlistedEndpointsPolicy
                    endpoints: []
                  outgoing:
                    dependencies:
                      - service: "echo"
                      - service: "echo2"
        """.trimIndent()

        private val echoConfig = Echo1EnvoyAuthConfig.copy(
            configOverride = proxySettings(unlistedEndpointsPolicy = "log"))

        private val echo2Config = Echo2EnvoyAuthConfig.copy(
            configOverride = proxySettings(unlistedEndpointsPolicy = "blockAndLog"))

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
                EnvoyControlRunnerTestApp(properties = properties(sourceIpClient.ipAddress()), consulPort = consulPort) },
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
    fun `echo should allow unlisted clients to access 'log-unlisted-clients' endpoint over http and log it`() {

        // when
        val echoResponse = callEnvoyIngress(envoy = echoEnvoy, path = "/log-unlisted-clients")

        // then
        assertThat(echoResponse).isOk().isFrom(echoLocalService)
        assertThat(echoEnvoy.ingressPlainHttpRequests).isOne()
        assertThat(echoEnvoy).hasOneAccessDenialWithActionLog(
                protocol = "http",
                path = "/log-unlisted-clients",
                method = "GET",
                clientName = "",
                clientIp = echoEnvoy.gatewayIp()
        )
    }


    @Test
    fun `echo should allow unlisted clients to access unlisted endpoint over http and log it`() {
        // when
        val echoResponse = callEnvoyIngress(envoy = echoEnvoy, path = "/unlisted-endpoint")

        // then
        assertThat(echoResponse).isOk()
        assertThat(echoEnvoy.ingressPlainHttpRequests).isOne()
        assertThat(echoEnvoy).hasOneAccessDenialWithActionLog(
                protocol = "http",
                path = "/unlisted-endpoint",
                method = "GET",
                clientName = "",
                clientIp = echoEnvoy.gatewayIp()
        )
    }

    @Test
    fun `echo should allow echo3 to access 'log-unlisted-clients' endpoint with wrong http method and log it`() {
        // when
        val echoResponse = callPost(service = "echo", from = echo3Envoy, path = "/log-unlisted-clients")

        // then
        assertThat(echoResponse).isOk()
        assertThat(echoEnvoy.ingressSslRequests).isOne()
        assertThat(echoEnvoy).hasOneAccessDenialWithActionLog(
            protocol = "https",
            path = "/log-unlisted-clients",
            method = "POST",
            clientName = "echo3",
            clientIp = echo3Envoy.ipAddress()
        )
    }

    @Test
    fun `echo should allow source-ip-client to access 'log-unlisted-clients' endpoint over http and log it`() {
        // when
        val echoResponse = callByProxy(proxy = sourceIpClientToEchoProxy, path = "/log-unlisted-clients")

        // then
        assertThat(echoResponse).isOk().isFrom(echoLocalService)
        assertThat(echoEnvoy.ingressPlainHttpRequests).isOne()
        assertThat(echoEnvoy).hasOneAccessDenialWithActionLog(
                protocol = "http",
                path = "/log-unlisted-clients",
                method = "GET",
                clientName = "",
                clientIp = sourceIpClient.ipAddress()
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

    private fun callByProxy(proxy: String, path: String) = call(
        address = proxy, pathAndQuery = path)

    private fun callPost(service: String, from: EnvoyContainer, path: String) = call(
        service = service, from = from, path = path, method = "POST",
        body = RequestBody.create(MediaType.get("text/plain"), "{}"))

    private val EnvoyContainer.ingressSslRequests: Int?
    get() = this.admin().statValue("http.ingress_https.downstream_rq_completed")?.toInt()

    private val EnvoyContainer.ingressPlainHttpRequests: Int?
        get() = this.admin().statValue("http.ingress_http.downstream_rq_completed")?.toInt()

    private fun EnvoyContainer.recordRBACLogs() {
        logRecorder.recordLogs { log -> log.contains("Access denied") }
    }
}
