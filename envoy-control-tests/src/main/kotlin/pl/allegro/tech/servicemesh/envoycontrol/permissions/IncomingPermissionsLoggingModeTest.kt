package pl.allegro.tech.servicemesh.envoycontrol.permissions

import okhttp3.MediaType
import okhttp3.RequestBody
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import pl.allegro.tech.servicemesh.envoycontrol.assertions.hasNoRBACDenials
import pl.allegro.tech.servicemesh.envoycontrol.assertions.hasOneAccessDenialWithActionBlock
import pl.allegro.tech.servicemesh.envoycontrol.assertions.hasOneAccessDenialWithActionLog
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isRbacAccessLog
import pl.allegro.tech.servicemesh.envoycontrol.config.ClientsFactory
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo1EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo2EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlRunnerTestApp
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration
import pl.allegro.tech.servicemesh.envoycontrol.config.containers.ToxiproxyContainer
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyContainer
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.EndpointMatch

@SuppressWarnings("LargeClass")
internal class IncomingPermissionsLoggingModeTest : EnvoyControlTestConfiguration() {

    companion object {
        private const val prefix = "envoy-control.envoy.snapshot"
        private val properties = { sourceClientIp: String -> mapOf(
            "$prefix.incoming-permissions.enabled" to true,
            "$prefix.incoming-permissions.source-ip-authentication.ip-from-range.source-ip-client" to
                "$sourceClientIp/32",
            "$prefix.routes.status.create-virtual-cluster" to true,
            "$prefix.routes.status.endpoints" to mutableListOf(EndpointMatch().also { it.path = "/status/" }),
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
    fun `should allow echo3 to access status endpoint over https`() {
        // when
        val echoResponse = call(service = "echo", from = echo3Envoy, path = "/status/hc")

        // then
        assertThat(echoResponse).isOk().isFrom(echoLocalService)
        assertThat(echoEnvoy.ingressSslRequests).isOne()
        assertThat(echoEnvoy).hasNoRBACDenials()

        // when
        val echo2Response = call(service = "echo2", from = echo3Envoy, path = "/status/hc")

        // then
        assertThat(echo2Response).isOk().isFrom(echo2LocalService)
        assertThat(echo2Envoy.ingressSslRequests).isOne()
        assertThat(echo2Envoy).hasNoRBACDenials()
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
            clientIp = echo2Envoy.ipAddress(),
            requestId = ""
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
            clientIp = echoEnvoy.ipAddress(),
            requestId = ""
        )
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
            clientIp = echoEnvoy.gatewayIp(),
            requestId = ""
        )
    }

    @Test
    fun `echo2 should NOT allow unlisted clients to access 'block-unlisted-clients' endpoint over http`() {
        // when
        val echo2Response = callEnvoyIngress(envoy = echo2Envoy, path = "/block-unlisted-clients")

        // then
        assertThat(echo2Response).isForbidden()
        assertThat(echo2Envoy.ingressPlainHttpRequests).isOne()
        assertThat(echo2Envoy).hasOneAccessDenialWithActionBlock(
            protocol = "http",
            path = "/block-unlisted-clients",
            method = "GET",
            clientName = "",
            clientIp = echo2Envoy.gatewayIp(),
            requestId = ""
        )
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
            clientIp = echoEnvoy.gatewayIp()
        )
    }

    @Test
    fun `echo2 should allow unlisted clients to access 'log-unlisted-clients' endpoint over http and log it`() {
        // when
        val echo2Response = callEnvoyIngress(envoy = echo2Envoy, path = "/log-unlisted-clients")

        // then
        assertThat(echo2Response).isOk().isFrom(echo2LocalService)
        assertThat(echo2Envoy.ingressPlainHttpRequests).isOne()
        assertThat(echo2Envoy).hasOneAccessDenialWithActionLog(
            protocol = "http",
            path = "/log-unlisted-clients",
            method = "GET",
            clientName = "",
            clientIp = echoEnvoy.gatewayIp()
        )
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
            clientIp = echo2Envoy.ipAddress(),
            requestId = ""
        )
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
            clientIp = echoEnvoy.ipAddress(),
            requestId = ""
        )
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
            clientIp = echoEnvoy.gatewayIp(),
            requestId = ""
        )
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
            clientIp = echo2Envoy.gatewayIp(),
            requestId = ""
        )
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
            clientIp = echo3Envoy.ipAddress(),
            requestId = ""
        )
    }

    @Test
    fun `echo2 should allow echo3 to access unlisted endpoint over https and log it`() {
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
            clientIp = echo3Envoy.ipAddress(),
            requestId = ""
        )
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
            clientIp = echo2Envoy.ipAddress(),
            requestId = ""
        )
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
            clientIp = echoEnvoy.ipAddress(),
            requestId = ""
        )
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
            clientIp = sourceIpClient.ipAddress(),
            requestId = ""
        )
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
            clientIp = sourceIpClient.ipAddress(),
            requestId = ""
        )
    }

    @Test
    fun `echo should NOT allow unlisted clients to access unlisted endpoint over http`() {
        // when
        val echoResponse = callEnvoyIngress(envoy = echoEnvoy, path = "/unlisted-endpoint")

        // then
        assertThat(echoResponse).isForbidden()
        assertThat(echoEnvoy.ingressPlainHttpRequests).isOne()
        assertThat(echoEnvoy).hasOneAccessDenialWithActionBlock(
            protocol = "http",
            path = "/unlisted-endpoint",
            method = "GET",
            clientName = "",
            clientIp = echoEnvoy.gatewayIp(),
            requestId = ""
        )
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
            clientIp = echo2Envoy.gatewayIp(),
            requestId = ""
        )
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
            clientIp = echo3Envoy.ipAddress(),
            requestId = ""
        )
    }

    @Test
    fun `echo2 should allow echo3 to access 'log-unlisted-clients' endpoint with wrong http method and log it`() {
        // when
        val echo2Response = callPost(service = "echo2", from = echo3Envoy, path = "/log-unlisted-clients")

        // then
        assertThat(echo2Response).isOk().isFrom(echo2LocalService)
        assertThat(echo2Envoy.ingressSslRequests).isOne()
        assertThat(echo2Envoy).hasOneAccessDenialWithActionLog(
            protocol = "https",
            path = "/log-unlisted-clients",
            method = "POST",
            clientName = "echo3",
            clientIp = echo3Envoy.ipAddress(),
            requestId = ""

        )
    }

    @Test
    fun `echo2 should allow unlisted client with client identity header over https and log client name as untrusted`() {
        // given
        val insecureClient = ClientsFactory.createInsecureClient()

        // when
        val echo2Response = callEnvoyIngress(
            envoy = echo2Envoy,
            path = "/log-unlisted-clients",
            headers = mapOf("x-service-name" to "service-name-from-header"),
            useSsl = true,
            client = insecureClient
        )

        // then
        assertThat(echo2Response).isOk().isFrom(echo2LocalService)
        assertThat(echo2Envoy.ingressSslRequests).isOne()
        assertThat(echo2Envoy).hasOneAccessDenialWithActionLog(
            protocol = "https",
            path = "/log-unlisted-clients",
            method = "GET",
            clientName = "service-name-from-header (not trusted)",
            clientIp = echo2Envoy.gatewayIp()
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
        logRecorder.recordLogs(::isRbacAccessLog)
    }
}
