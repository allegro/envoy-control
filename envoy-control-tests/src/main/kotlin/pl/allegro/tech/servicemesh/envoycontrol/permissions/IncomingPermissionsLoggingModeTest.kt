package pl.allegro.tech.servicemesh.envoycontrol

import okhttp3.MediaType
import okhttp3.RequestBody
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
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
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
internal class IncomingPermissionsLoggingModeTest : EnvoyControlTestConfiguration() {

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
                    endpoints:
                    - path: "/block-unlisted-clients"
                      clients: ["authorized-clients"]
                      unlistedClientsPolicy: blockAndLog
                    - path: "/log-unlisted-clients"
                      methods: [GET]
                      clients: ["authorized-clients"]
                      unlistedClientsPolicy: log
                    - path: "/block-unlisted-clients-by-default"
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
                EnvoyControlRunnerTestApp(properties = properties(sourceIpClient.ipAddress()), consulPort = consulPort) },
                envoys = 2,
                envoyConfig = echoConfig,
                secondEnvoyConfig = echo2Config
            )
            echo3Envoy = createEnvoyContainerWithEcho3Certificate(configOverride = echo3Config)
            echo3Envoy.start()

            registerServiceWithEnvoyOnIngress(name = "echo", envoy = echoEnvoy, tags = listOf("mtls:enabled"))
            registerServiceWithEnvoyOnIngress(name = "echo2", envoy = echo2Envoy, tags = listOf("mtls:enabled"))
        }
    }

    /**
     * We arbitrary choose to run this test first. It acts as a setup phase for all tests.
     * This way we don't have to use untilAsserted loop in any other tests, because after this test we are sure, that
     * everything is initialized
     */
    @Test
    @Order(0)
    fun `should allow echo3 to access status endpoint over https`() {

        untilAsserted {
            echoEnvoy.admin().resetCounters()

            // when
            val echoResponse = call(service = "echo", from = echo3Envoy, path = "/status/hc")

            // then
            assertThat(echoResponse).isOk().isFrom(echoLocalService)
            assertThat(echoEnvoy.ingressSslRequests).isOne()
            assertThat(echoEnvoy).hasNoRBACDenials()
        } // aaacab33-caeb-468a-9f22-a0679eb87832

        untilAsserted {
            echo2Envoy.admin().resetCounters()

            // when
            val echo2Response = call(service = "echo2", from = echo3Envoy, path = "/status/hc")

            // then
            assertThat(echo2Response).isOk().isFrom(echo2LocalService)
            assertThat(echo2Envoy.ingressSslRequests).isOne()
            assertThat(echo2Envoy).hasNoRBACDenials()
        }
    }

    @Test
    fun `echo should allow access to status endpoint by all clients over http`() {
        // when
        val echoResponse = callEnvoyIngress(envoy = echoEnvoy, path = "/status/hc")

        // then
        assertThat(echoResponse).isOk().isFrom(echoLocalService)
        assertThat(echoEnvoy).hasNoRBACDenials()
    }

    @Test
    fun `echo2 should allow access to status endpoint by any client over http`() {
        // when
        val echo2Response = callEnvoyIngress(envoy = echo2Envoy, path = "/status/hc", useSsl = false)

        // then
        assertThat(echo2Response).isOk().isFrom(echo2LocalService)
        assertThat(echo2Envoy).hasNoRBACDenials()
    }

    @Test
    fun `echo should allow echo3 to access 'block-unlisted-clients' endpoint over https`() {
        // when
        val echoResponse = call(service = "echo", from = echo3Envoy, path = "/block-unlisted-clients")

        // then
        assertThat(echoResponse).isOk().isFrom(echoLocalService)
        assertThat(echoEnvoy.ingressSslRequests).isOne()
        assertThat(echoEnvoy).hasNoRBACDenials()
    }

    @Test
    fun `echo2 should allow echo3 to access 'block-unlisted-clients' endpoint over https`() {
        // when
        val echo2Response = call(service = "echo2", from = echo3Envoy, path = "/block-unlisted-clients")

        // then
        assertThat(echo2Response).isOk().isFrom(echo2LocalService)
        assertThat(echo2Envoy.ingressSslRequests).isOne()
        assertThat(echo2Envoy).hasNoRBACDenials()
    }

    @Test
    fun `echo should NOT allow echo2 to access 'block-unlisted-clients' endpoint over https`() {
        // when
        val echoResponse = call(service = "echo", from = echo2Envoy, path = "/block-unlisted-clients")

        // then
        assertThat(echoResponse).isForbidden()
        assertThat(echoEnvoy.ingressSslRequests).isOne()
        assertThat(echoEnvoy).hasOneAccessDenialWithActionBlock(
            protocol = "https",
            path = "/block-unlisted-clients",
            method = "GET",
            clientName = "echo2",
            clientIp = echo2Envoy.ipAddress()
        )
    }

    @Test
    fun `echo2 should NOT allow echo to access 'block-unlisted-clients' endpoint over https`() {
        // when
        val echo2Response = call(service = "echo2", from = echoEnvoy, path = "/block-unlisted-clients")

        // then
        assertThat(echo2Response).isForbidden()
        assertThat(echo2Envoy.ingressSslRequests).isOne()
        assertThat(echo2Envoy).hasOneAccessDenialWithActionBlock(
            protocol = "https",
            path = "/block-unlisted-clients",
            method = "GET",
            clientName = "echo",
            clientIp = echoEnvoy.ipAddress())
    }

    @Test
    fun `echo should allow source-ip-client to access 'block-unlisted-clients' endpoint over http`() {
        // when
        val echoResponse = callByProxy(proxy = sourceIpClientToEchoProxy, path = "/block-unlisted-clients")

        // then
        assertThat(echoResponse).isOk().isFrom(echoLocalService)
        assertThat(echoEnvoy.ingressPlainHttpRequests).isOne()
        assertThat(echoEnvoy).hasNoRBACDenials()
    }

    @Test
    fun `echo2 should allow source-ip-client to access 'block-unlisted-clients' endpoint over http`() {
        // when
        val echo2Response = callByProxy(proxy = sourceIpClientToEcho2Proxy, path = "/block-unlisted-clients")

        // then
        assertThat(echo2Response).isOk().isFrom(echo2LocalService)
        assertThat(echo2Envoy.ingressPlainHttpRequests).isOne()
        assertThat(echo2Envoy).hasNoRBACDenials()
    }

    @Test
    fun `echo should NOT allow unlisted clients to access 'block-unlisted-clients' endpoint over http`() {
        // when
        val echoResponse = callEnvoyIngress(envoy = echoEnvoy, path = "/block-unlisted-clients")

        // then
        assertThat(echoResponse).isForbidden()
        assertThat(echoEnvoy.ingressPlainHttpRequests).isOne()
        assertThat(echoEnvoy).hasOneAccessDenialWithActionBlock(
            protocol = "http",
            path = "/block-unlisted-clients",
            method = "GET",
            clientName = "",
            clientIp = echoEnvoy.hostIp())
    }

    @Test
    fun `echo2 should NOT allow unlisted clients to access 'block-unlisted-clients' endpoint over http`() {
        // when
        val echo2Response = callEnvoyIngress(envoy = echo2Envoy, path = "/block-unlisted-clients")

        // then
        assertThat(echo2Response).isForbidden()
        assertThat(echo2Envoy.ingressPlainHttpRequests).isOne()
        assertThat(echo2Envoy).hasOneAccessDenialWithActionBlock(protocol = "http",
            path = "/block-unlisted-clients",
            method = "GET",
            clientName = "",
            clientIp = echo2Envoy.hostIp())
    }

    @Test
    fun `echo should allow echo3 to access 'log-unlisted-clients' endpoint over https`() {
        // when
        val echoResponse = call(service = "echo", from = echo3Envoy, path = "/log-unlisted-clients")

        // then
        assertThat(echoResponse).isOk().isFrom(echoLocalService)
        assertThat(echoEnvoy.ingressSslRequests).isOne()
        assertThat(echoEnvoy).hasNoRBACDenials()
    }

    @Test
    fun `echo2 should allow echo3 to access 'log-unlisted-clients' endpoint over https`() {
        // when
        val echo2Response = call(service = "echo2", from = echo3Envoy, path = "/log-unlisted-clients")

        // then
        assertThat(echo2Response).isOk().isFrom(echo2LocalService)
        assertThat(echo2Envoy.ingressSslRequests).isOne()
        assertThat(echo2Envoy).hasNoRBACDenials()
    }

    @Test
    fun `echo should allow echo2 to access 'log-unlisted-clients' endpoint over https and log it`() {
        // when
        val echoResponse = call(service = "echo", from = echo2Envoy, path = "/log-unlisted-clients")

        // then
        assertThat(echoResponse).isOk().isFrom(echoLocalService)
        assertThat(echoEnvoy.ingressSslRequests).isOne()
        assertThat(echoEnvoy).hasOneAccessDenialWithActionLog(
            protocol = "https",
            path = "/log-unlisted-clients",
            method = "GET",
            clientName = "echo2",
            clientIp = echo2Envoy.ipAddress())
    }

    @Test
    fun `echo2 should allow echo to access 'log-unlisted-clients' endpoint over https and log it`() {
        // when
        val echo2Response = call(service = "echo2", from = echoEnvoy, path = "/log-unlisted-clients")

        // then
        assertThat(echo2Response).isOk().isFrom(echo2LocalService)
        assertThat(echo2Envoy.ingressSslRequests).isOne()
        assertThat(echo2Envoy).hasOneAccessDenialWithActionLog(
            protocol = "https",
            path = "/log-unlisted-clients",
            method = "GET",
            clientName = "echo",
            clientIp = echoEnvoy.ipAddress())
    }

    @Test
    fun `echo should allow source-ip-client to access 'log-unlisted-clients' endpoint over http`() {
        // when
        val echoResponse = callByProxy(proxy = sourceIpClientToEchoProxy, path = "/log-unlisted-clients")

        // then
        assertThat(echoResponse).isOk().isFrom(echoLocalService)
        assertThat(echoEnvoy.ingressPlainHttpRequests).isOne()
        assertThat(echoEnvoy).hasNoRBACDenials()
    }

    @Test
    fun `echo2 should allow source-ip-client to access 'log-unlisted-clients' endpoint over http`() {
        // when
        val echo2Response = callByProxy(proxy = sourceIpClientToEcho2Proxy, path = "/log-unlisted-clients")

        // then
        assertThat(echo2Response).isOk().isFrom(echo2LocalService)
        assertThat(echo2Envoy.ingressPlainHttpRequests).isOne()
        assertThat(echo2Envoy).hasNoRBACDenials()
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
            clientIp = echoEnvoy.hostIp())
    }

    @Test
    fun `echo2 should allow unlisted clients to access 'log-unlisted-clients' endpoint over http and log it`() {
        // when
        val echo2Response = callEnvoyIngress(envoy = echo2Envoy, path = "/log-unlisted-clients")

        // then
        assertThat(echo2Response).isOk().isFrom(echo2LocalService)
        assertThat(echo2Envoy.ingressPlainHttpRequests).isOne()
        assertThat(echo2Envoy).hasOneAccessDenialWithActionBlock(
            protocol = "http",
            path = "/log-unlisted-clients",
            method = "GET",
            clientName = "",
            clientIp = echoEnvoy.hostIp())
    }

    @Test
    fun `echo should allow echo3 to access 'block-unlisted-clients-by-default' endpoint over https`() {
        // when
        val echoResponse = call(service = "echo", from = echo3Envoy, path = "/block-unlisted-clients-by-default")

        // then
        assertThat(echoResponse).isOk().isFrom(echoLocalService)
        assertThat(echoEnvoy.ingressSslRequests).isOne()
        assertThat(echoEnvoy).hasNoRBACDenials()
    }

    @Test
    fun `echo2 should allow echo3 to access 'block-unlisted-clients-by-default' endpoint over https`() {
        // when
        val echo2Response = call(service = "echo2", from = echo3Envoy, path = "/block-unlisted-clients-by-default")

        // then
        assertThat(echo2Response).isOk().isFrom(echo2LocalService)
        assertThat(echo2Envoy.ingressSslRequests).isOne()
        assertThat(echo2Envoy).hasNoRBACDenials()
    }

    @Test
    fun `echo should NOT allow echo2 to access 'block-unlisted-clients-by-default' endpoint over https`() {
        // when
        val echoResponse = call(service = "echo", from = echo2Envoy, path = "/block-unlisted-clients-by-default")

        // then
        assertThat(echoResponse).isForbidden()
        assertThat(echoEnvoy.ingressSslRequests).isOne()
        assertThat(echoEnvoy).hasOneAccessDenialWithActionBlock(
            protocol = "https",
            path = "/block-unlisted-clients-by-default",
            method = "GET",
            clientName = "echo2",
            clientIp = echo2Envoy.ipAddress())
    }

    @Test
    fun `echo2 should NOT allow echo to access 'block-unlisted-clients-by-default' endpoint over https`() {
        // when
        val echo2Response = call(service = "echo2", from = echoEnvoy, path = "/block-unlisted-clients-by-default")

        // then
        assertThat(echo2Response).isForbidden()
        assertThat(echo2Envoy.ingressSslRequests).isOne()
        assertThat(echo2Envoy).hasOneAccessDenialWithActionBlock(
            protocol = "https",
            path = "/block-unlisted-clients-by-default",
            method = "GET",
            clientName = "echo",
            clientIp = echoEnvoy.ipAddress())
    }

    @Test
    fun `echo should allow source-ip-client to access 'block-unlisted-clients-by-default' endpoint over http`() {
        // when
        val echoResponse = callByProxy(proxy = sourceIpClientToEchoProxy, path = "/block-unlisted-clients-by-default")

        // then
        assertThat(echoResponse).isOk().isFrom(echoLocalService)
        assertThat(echoEnvoy.ingressPlainHttpRequests).isOne()
        assertThat(echoEnvoy).hasNoRBACDenials()
    }

    @Test
    fun `echo2 should allow source-ip-client to access 'block-unlisted-clients-by-default' endpoint over http`() {
        // when
        val echo2Response = callByProxy(proxy = sourceIpClientToEcho2Proxy, path = "/block-unlisted-clients-by-default")

        // then
        assertThat(echo2Response).isOk().isFrom(echo2LocalService)
        assertThat(echo2Envoy.ingressPlainHttpRequests).isOne()
        assertThat(echo2Envoy).hasNoRBACDenials()
    }

    @Test
    fun `echo should NOT allow unlisted clients to access 'block-unlisted-clients-by-default' endpoint over http`() {
        // when
        val echoResponse = callEnvoyIngress(envoy = echoEnvoy, path = "/block-unlisted-clients-by-default")

        // then
        assertThat(echoResponse).isForbidden()
        assertThat(echoEnvoy.ingressPlainHttpRequests).isOne()
        assertThat(echoEnvoy).hasOneAccessDenialWithActionBlock(
            protocol = "http",
            path = "/block-unlisted-clients-by-default",
            method = "GET",
            clientName = "",
            clientIp = echoEnvoy.hostIp())
    }

    @Test
    fun `echo2 should NOT allow unlisted clients to access 'block-unlisted-clients-by-default' endpoint over http`() {
        // when
        val echo2Response = callEnvoyIngress(envoy = echo2Envoy, path = "/block-unlisted-clients-by-default")

        // then
        assertThat(echo2Response).isForbidden()
        assertThat(echo2Envoy.ingressPlainHttpRequests).isOne()
        assertThat(echo2Envoy).hasOneAccessDenialWithActionBlock(
            protocol = "http",
            path = "/block-unlisted-clients-by-default",
            method = "GET",
            clientName = "",
            clientIp = echo2Envoy.hostIp())
    }

    @Test
    fun `echo should NOT allow echo3 to access unlisted endpoint over https`() {
        // when
        val echoResponse = call(service = "echo", from = echo3Envoy, path = "/unlisted-endpoint")

        // then
        assertThat(echoResponse).isForbidden()
        assertThat(echoEnvoy.ingressSslRequests).isOne()
        assertThat(echoEnvoy).hasOneAccessDenialWithActionBlock(
            protocol = "https",
            path = "/unlisted-endpoint",
            method = "GET",
            clientName = "echo3",
            clientIp = echo3Envoy.ipAddress())
    }

    @Test
    fun `echo2 should allow echo3 to access unlisted endpoint over https and log it`() {
        // TODO untilAsserted added just for be able to run this single test
        untilAsserted {
            val echo2Response = call(service = "echo2", from = echo3Envoy, path = "/unlisted-endpoint")
            assertThat(echo2Response).isOk()
            echoEnvoy.admin().resetCounters()
            echo2Envoy.admin().resetCounters()
            echo3Envoy.admin().resetCounters()
        }

        // when
        val echo2Response = call(service = "echo2", from = echo3Envoy, path = "/unlisted-endpoint")

        // then
        assertThat(echo2Response).isOk().isFrom(echo2LocalService)
        assertThat(echo2Envoy.ingressSslRequests).isOne()
        assertThat(echo2Envoy).hasOneAccessDenialWithActionLog(
            protocol = "https",
            path = "/unlisted-endpoint",
            method = "GET",
            clientName = "echo3",
            clientIp = echo3Envoy.ipAddress())
    }

    @Test
    fun `echo should NOT allow echo2 to access unlisted endpoint over https`() {
        // when
        val echoResponse = call(service = "echo", from = echo2Envoy, path = "/unlisted-endpoint")

        // then
        assertThat(echoResponse).isForbidden()
        assertThat(echoEnvoy.ingressSslRequests).isOne()
        assertThat(echoEnvoy).hasOneAccessDenialWithActionBlock(
            protocol = "https",
            path = "/unlisted-endpoint",
            method = "GET",
            clientName = "echo2",
            clientIp = echo2Envoy.ipAddress())
    }

    @Test
    fun `echo2 should allow echo to access unlisted endpoint over https and log it`() {
        // when
        val echo2Response = call(service = "echo2", from = echoEnvoy, path = "/unlisted-endpoint")

        // then
        assertThat(echo2Response).isOk().isFrom(echo2LocalService)
        assertThat(echo2Envoy.ingressSslRequests).isOne()
        assertThat(echo2Envoy).hasOneAccessDenialWithActionLog(
            protocol = "https",
            path = "/unlisted-endpoint",
            method = "GET",
            clientName = "echo",
            clientIp = echoEnvoy.ipAddress())
    }

    @Test
    fun `echo should NOT allow source-ip-client to access unlisted endpoint over http`() {
        // when
        val echoResponse = callByProxy(proxy = sourceIpClientToEchoProxy, path = "/unlisted-endpoint")

        // then
        assertThat(echoResponse).isForbidden()
        assertThat(echoEnvoy.ingressPlainHttpRequests).isOne()
        assertThat(echoEnvoy).hasOneAccessDenialWithActionBlock(
            protocol = "http",
            path = "/unlisted-endpoint",
            method = "GET",
            clientName = "",
            clientIp = sourceIpClient.ipAddress())
    }

    @Test
    fun `echo2 should allow source-ip-client to access unlisted endpoint over http`() {
        // when
        val echo2Response = callByProxy(proxy = sourceIpClientToEcho2Proxy, path = "/unlisted-endpoint")

        // then
        assertThat(echo2Response).isOk().isFrom(echo2LocalService)
        assertThat(echo2Envoy.ingressPlainHttpRequests).isOne()
        assertThat(echo2Envoy).hasOneAccessDenialWithActionLog(
            protocol = "http",
            path = "/unlisted-endpoint",
            method = "GET",
            clientName = "",
            clientIp = sourceIpClient.ipAddress())
    }

    @Test
    fun `echo should NOT allow unlisted clients to access unlisted endpoint over http`() {
        // when
        val echoResponse = callEnvoyIngress(envoy = echo2Envoy, path = "/unlisted-endpoint")

        // then
        assertThat(echoResponse).isForbidden()
        assertThat(echoEnvoy.ingressPlainHttpRequests).isOne()
        assertThat(echoEnvoy).hasOneAccessDenialWithActionBlock(
            protocol = "http",
            path = "/unlisted-endpoint",
            method = "GET",
            clientName = "",
            clientIp = echoEnvoy.hostIp())
    }

    @Test
    fun `echo2 should allow unlisted clients to access unlisted endpoint over http`() {
        // when
        val echo2Response = callEnvoyIngress(envoy = echo2Envoy, path = "/unlisted-endpoint")

        // then
        assertThat(echo2Response).isOk().isFrom(echo2LocalService)
        assertThat(echo2Envoy.ingressPlainHttpRequests).isOne()
        assertThat(echo2Envoy).hasOneAccessDenialWithActionLog(
            protocol = "http",
            path = "/unlisted-endpoint",
            method = "GET",
            clientName = "",
            clientIp = echo2Envoy.hostIp())
    }

    @Test
    fun `echo should NOT allow echo3 to access 'log-unlisted-clients' endpoint with wrong http method`() {
        // when
        val echoResponse = callPost(service = "echo", from = echo3Envoy, path = "/log-unlisted-clients")

        // then
        assertThat(echoResponse).isForbidden()
        assertThat(echoEnvoy.ingressSslRequests).isOne()
        assertThat(echoEnvoy).hasOneAccessDenialWithActionBlock(
            protocol = "https",
            path = "/log-unlisted-clients",
            method = "POST",
            clientName = "echo3",
            clientIp = echo3Envoy.ipAddress())
    }

    @Test
    fun `echo2 should allow echo3 to access 'log-unlisted-clients' endpoint with wrong http method and log it`() {
        // when
        val echo2Response = callPost(service = "echo2", from = echo3Envoy, path = "/log-unlisted-clients")

        // then
        assertThat(echo2Response).isOk().isFrom(echo2LocalService)
        assertThat(echo2Envoy.ingressSslRequests).isOne()
        assertThat(echo2Envoy).hasOneAccessDenialWithActionBlock(
            protocol = "https",
            path = "/log-unlisted-clients",
            method = "POST",
            clientName = "echo3",
            clientIp = echo3Envoy.ipAddress())
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
        // TODO: correct predicate
        logRecorder.recordLogs { log -> log.contains("Access denied") }
    }
}
