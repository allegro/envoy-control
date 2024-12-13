package pl.allegro.tech.servicemesh.envoycontrol.permissions

import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.testcontainers.junit.jupiter.Testcontainers
import pl.allegro.tech.servicemesh.envoycontrol.assertions.hasNoRBACDenials
import pl.allegro.tech.servicemesh.envoycontrol.assertions.hasOneAccessDenialWithActionBlock
import pl.allegro.tech.servicemesh.envoycontrol.assertions.hasOneAccessDenialWithActionLog
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isForbidden
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isFrom
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isOk
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo1EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo2EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo3EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulMultiClusterExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.containers.ProxyOperations
import pl.allegro.tech.servicemesh.envoycontrol.config.containers.ToxiproxyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyContainer
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlClusteredExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.EndpointMatch
import java.util.UUID

@Testcontainers
class IncomingPermissionsLoggingModeTest {

    companion object {
        private const val prefix = "envoy-control.envoy.snapshot"
        private val properties = { sourceClientIp: String ->
            mapOf(
                "$prefix.incoming-permissions.enabled" to true,
                "$prefix.incoming-permissions.overlapping-paths-fix" to true,
                "$prefix.incoming-permissions.source-ip-authentication.ip-from-range.source-ip-client" to
                    "$sourceClientIp/32",
                "$prefix.routes.status.create-virtual-cluster" to true,
                "$prefix.routes.status.endpoints" to mutableListOf(EndpointMatch().also { it.path = "/status/" }),
                "$prefix.routes.status.enabled" to true,
                "$prefix.incoming-permissions.clients-allowed-to-all-endpoints" to listOf("allowed-client")
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

        @JvmField
        @RegisterExtension
        val sourceIpClient = ToxiproxyExtension(exposedPortsCount = 2)

        @JvmField
        @RegisterExtension
        val consul = ConsulMultiClusterExtension()

        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlClusteredExtension(
            consul.serverFirst,
            propertiesProvider = { properties(sourceIpClient.container.ipAddress()) },
            dependencies = listOf(consul, sourceIpClient)
        )

        @JvmField
        @RegisterExtension
        val echoLocalService = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val echo2LocalService = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val localService = EchoServiceExtension()

        // 1. envoy
        private val echoConfig =
            Echo1EnvoyAuthConfig.copy(configOverride = proxySettings(unlistedEndpointsPolicy = "blockAndLog"))

        @JvmField
        @RegisterExtension
        val echoEnvoy = EnvoyExtension(envoyControl, echoLocalService, echoConfig)

        // 2. envoy
        private val echo2Config =
            Echo2EnvoyAuthConfig.copy(configOverride = proxySettings(unlistedEndpointsPolicy = "log"))

        @JvmField
        @RegisterExtension
        val echo2Envoy = EnvoyExtension(envoyControl, echo2LocalService, echo2Config)

        // 3. envoy with cert
        val echo3EnvoyConfig = Echo3EnvoyAuthConfig.copy(configOverride = echo3Config)

        @JvmField
        @RegisterExtension
        val echo3Envoy = EnvoyExtension(envoyControl, localService, echo3EnvoyConfig)

        val sourceIpClientToEchoProxy by lazy { ProxyOperations(createProxyToEnvoyIngress(sourceIpClient, echoEnvoy)) }
        val sourceIpClientToEcho2Proxy by lazy {
            ProxyOperations(
                createProxyToEnvoyIngress(
                    sourceIpClient,
                    echo2Envoy
                )
            )
        }

        fun createProxyToEnvoyIngress(toxiproxy: ToxiproxyExtension, envoy: EnvoyExtension) =
            toxiproxy.container.createProxy(
                targetIp = envoy.container.ipAddress(),
                targetPort = EnvoyContainer.INGRESS_LISTENER_CONTAINER_PORT
            )
    }

    @BeforeEach
    fun startRecordingRBACLogs() {
        registerServiceWithEnvoyOnIngress(name = "echo", envoy = echoEnvoy, tags = listOf("mtls:enabled"))
        registerServiceWithEnvoyOnIngress(name = "echo2", envoy = echo2Envoy, tags = listOf("mtls:enabled"))
        waitForEnvoysInitialized()
        echoEnvoy.recordRBACLogs()
        echo2Envoy.recordRBACLogs()
    }

    @AfterEach
    fun cleanupTest() {
        echoEnvoy.stopRecordingRBAC()
        echo2Envoy.stopRecordingRBAC()
    }

    fun registerServiceWithEnvoyOnIngress(name: String, envoy: EnvoyExtension, tags: List<String>) {
        val id = UUID.randomUUID().toString()
        consul.serverFirst.operations.registerService(
            id = id,
            name = name,
            address = envoy.container.ipAddress(),
            port = EnvoyContainer.INGRESS_LISTENER_CONTAINER_PORT,
            tags = tags
        )
    }

    private fun waitForEnvoysInitialized() {
        untilAsserted {
            assertThat(echo3Envoy.container.admin().isEndpointHealthy("echo", echoEnvoy.container.ipAddress())).isTrue()
            assertThat(
                echo3Envoy.container.admin().isEndpointHealthy("echo2", echo2Envoy.container.ipAddress())
            ).isTrue()
            assertThat(
                echoEnvoy.container.admin().isEndpointHealthy("echo2", echo2Envoy.container.ipAddress())
            ).isTrue()
            assertThat(echo2Envoy.container.admin().isEndpointHealthy("echo", echoEnvoy.container.ipAddress())).isTrue()
        }
    }

    @Test
    fun `should allow echo3 to access status endpoint over https`() {
        // when
        val echoResponse = echo3Envoy.egressOperations.callService(service = "echo", pathAndQuery = "/status/hc")

        // then
        assertThat(echoResponse).isOk().isFrom(echoLocalService)
        assertThat(echoEnvoy.container.admin().statValue("http.ingress_https.downstream_rq_completed")?.toInt()).isOne()
        assertThat(echoEnvoy.container).hasNoRBACDenials()

        // when
        val echo2Response = echo3Envoy.egressOperations.callService(service = "echo2", pathAndQuery = "/status/hc")

        // then
        assertThat(echo2Response).isOk().isFrom(echo2LocalService)
        assertThat(echo2Envoy.container.admin().statValue("http.ingress_https.downstream_rq_completed")?.toInt()).isOne()
        assertThat(echo2Envoy.container).hasNoRBACDenials()
    }

    @Test
    fun `echo should allow access to status endpoint by all clients over http`() {
        // when
        val echoResponse = echoEnvoy.ingressOperations.callLocalService("/status/hc")

        // then
        assertThat(echoResponse).isOk().isFrom(echoLocalService)
        assertThat(echoEnvoy.container).hasNoRBACDenials()
    }

    @Test
    fun `echo2 should allow access to status endpoint by any client over http`() {
        // when
        val echo2Response = echo2Envoy.ingressOperations.callLocalService("/status/hc")

        // then
        assertThat(echo2Response).isOk().isFrom(echo2LocalService)
        assertThat(echoEnvoy.container).hasNoRBACDenials()
    }

    @Test
    fun `echo should allow echo3 to access 'block-unlisted-clients' endpoint over https`() {
        // when
        val echoResponse =
            echo3Envoy.egressOperations.callService(service = "echo", pathAndQuery = "/block-unlisted-clients")

        // then
        assertThat(echoResponse).isOk().isFrom(echoLocalService)
        assertThat(echoEnvoy.container.admin().statValue("http.ingress_https.downstream_rq_completed")?.toInt()).isOne()
        assertThat(echoEnvoy.container).hasNoRBACDenials()
    }

    @Test
    fun `echo2 should allow echo3 to access 'block-unlisted-clients' endpoint over https`() {
        // when
        val echoResponse2 =
            echo3Envoy.egressOperations.callService(service = "echo2", pathAndQuery = "/block-unlisted-clients")

        // then
        assertThat(echoResponse2).isOk().isFrom(echo2LocalService)
        assertThat(echo2Envoy.container.admin().statValue("http.ingress_https.downstream_rq_completed")?.toInt()).isOne()
        assertThat(echo2Envoy.container).hasNoRBACDenials()
    }

    @Test
    fun `echo should NOT allow echo2 to access 'block-unlisted-clients' endpoint over https`() {
        // when
        val echoResponse = echo2Envoy.egressOperations.callService("echo", pathAndQuery = "/block-unlisted-clients")

        // then
        assertThat(echoResponse).isForbidden()
        assertThat(echoEnvoy.container.admin().statValue("http.ingress_https.downstream_rq_completed")?.toInt()).isOne()
        assertThat(echoEnvoy.container).hasOneAccessDenialWithActionBlock(
            protocol = "https",
            rule = "{\"path\":\"/block-unlisted-clients\",\"pathMatchingType\":\"PATH\",\"clients\":[{\"name\":\"authorized-clients\",\"negated\":false}],\"unlistedClientsPolicy\":\"BLOCKANDLOG\"}",
            path = "/block-unlisted-clients",
            method = "GET",
            clientName = "echo2",
            trustedClient = true,
            authority = "echo",
            clientIp = echo2Envoy.container.ipAddress()
        )
    }

    @Test
    fun `echo2 should NOT allow echo to access 'block-unlisted-clients' endpoint over https`() {
        // when
        val echo2Response = echoEnvoy.egressOperations.callService("echo2", pathAndQuery = "/block-unlisted-clients")

        // then
        assertThat(echo2Response).isForbidden()
        assertThat(echo2Envoy.container.admin().statValue("http.ingress_https.downstream_rq_completed")?.toInt()).isOne()
        assertThat(echo2Envoy.container).hasOneAccessDenialWithActionBlock(
            protocol = "https",
            rule = "{\"path\":\"/block-unlisted-clients\",\"pathMatchingType\":\"PATH\",\"clients\":[{\"name\":\"authorized-clients\",\"negated\":false}],\"unlistedClientsPolicy\":\"BLOCKANDLOG\"}",
            path = "/block-unlisted-clients",
            method = "GET",
            clientName = "echo",
            authority = "echo2",
            trustedClient = true,
            clientIp = echoEnvoy.container.ipAddress()
        )
    }

    @Test
    fun `echo should allow source-ip-client to access 'block-unlisted-clients-by-default' endpoint over http`() {
        // when
        val echoResponse = sourceIpClientToEchoProxy.call("/block-unlisted-clients-by-default")

        // then
        assertThat(echoResponse).isOk().isFrom(echoLocalService)
        assertThat(echoEnvoy.container.admin().statValue("http.ingress_http.downstream_rq_completed")?.toInt()).isOne()
        assertThat(echoEnvoy.container).hasNoRBACDenials()
    }

    @Test
    fun `echo2 should allow source-ip-client to access 'block-unlisted-clients-by-default' endpoint over http`() {
        // when
        val echo2Response = sourceIpClientToEcho2Proxy.call("/block-unlisted-clients-by-default")

        // then
        assertThat(echo2Response).isOk().isFrom(echo2LocalService)
        assertThat(echo2Envoy.container.admin().statValue("http.ingress_http.downstream_rq_completed")?.toInt()).isOne()
        assertThat(echo2Envoy.container).hasNoRBACDenials()
    }

    @Test
    fun `echo should NOT allow unlisted clients to access 'block-unlisted-clients-by-default' endpoint over http`() {
        // when
        val echoResponse = echoEnvoy.ingressOperations.callLocalService("/block-unlisted-clients-by-default")

        // then
        assertThat(echoResponse).isForbidden()
        assertThat(echoEnvoy.container.admin().statValue("http.ingress_http.downstream_rq_completed")?.toInt()).isOne()
        assertThat(echoEnvoy.container).hasOneAccessDenialWithActionBlock(
            protocol = "http",
            rule = "{\"path\":\"/block-unlisted-clients-by-default\",\"pathMatchingType\":\"PATH\",\"clients\":[{\"name\":\"authorized-clients\",\"negated\":false}]}",
            path = "/block-unlisted-clients-by-default",
            method = "GET",
            clientName = "",
            authority = echoEnvoy.container.ingressHost(),
            trustedClient = false,
            clientIp = echoEnvoy.container.gatewayIp()
        )
    }

    @Test
    fun `echo2 should NOT allow unlisted clients to access 'block-unlisted-clients' endpoint over http`() {
        // when
        val echo2Response = echo2Envoy.ingressOperations.callLocalService("/block-unlisted-clients")

        // then
        assertThat(echo2Response).isForbidden()
        assertThat(echo2Envoy.container.admin().statValue("http.ingress_http.downstream_rq_completed")?.toInt()).isOne()
        assertThat(echo2Envoy.container).hasOneAccessDenialWithActionBlock(
            protocol = "http",
            rule = "{\"path\":\"/block-unlisted-clients\",\"pathMatchingType\":\"PATH\",\"clients\":[{\"name\":\"authorized-clients\",\"negated\":false}],\"unlistedClientsPolicy\":\"BLOCKANDLOG\"}",
            path = "/block-unlisted-clients",
            method = "GET",
            clientName = "",
            authority = echo2Envoy.container.ingressHost(),
            trustedClient = false,
            clientIp = echo2Envoy.container.gatewayIp()
        )
    }

    @Test
    fun `echo should allow echo3 to access 'log-unlisted-clients' endpoint over https`() {
        // when
        val echoResponse = echo3Envoy.egressOperations.callService("echo", pathAndQuery = "/log-unlisted-clients")

        // then
        assertThat(echoResponse).isOk().isFrom(echoLocalService)
        assertThat(echoEnvoy.container.admin().statValue("http.ingress_https.downstream_rq_completed")?.toInt()).isOne()
        assertThat(echoEnvoy.container).hasNoRBACDenials()
    }

    @Test
    @Tag("flaky")
    fun `echo2 should allow echo3 to access 'log-unlisted-clients' endpoint over https`() {
        // when
        val echo2Response = echo3Envoy.egressOperations.callService("echo2", pathAndQuery = "/log-unlisted-clients")

        // then
        assertThat(echo2Response).isOk().isFrom(echo2LocalService)
        assertThat(echo2Envoy.container.admin().statValue("http.ingress_https.downstream_rq_completed")?.toInt()).isOne()
        assertThat(echo2Envoy.container).hasNoRBACDenials()
    }

    @Test
    fun `echo should allow echo2 to access 'log-unlisted-clients' endpoint over https and log it`() {
        // when
        val echoResponse = echo2Envoy.egressOperations.callService("echo", pathAndQuery = "/log-unlisted-clients")

        // then
        assertThat(echoResponse).isOk().isFrom(echoLocalService)
        assertThat(echoEnvoy.container.admin().statValue("http.ingress_https.downstream_rq_completed")?.toInt()).isOne()
        assertThat(echoEnvoy.container).hasOneAccessDenialWithActionLog(
            protocol = "https",
            rule = "{\"path\":\"/log-unlisted-clients\",\"pathMatchingType\":\"PATH\",\"methods\":[\"GET\"],\"clients\":[{\"name\":\"authorized-clients\",\"negated\":false}],\"unlistedClientsPolicy\":\"BLOCKANDLOG\"}",
            path = "/log-unlisted-clients",
            method = "GET",
            clientName = "echo2",
            authority = "echo",
            trustedClient = true,
            clientIp = echo2Envoy.container.ipAddress()
        )
    }

    @Test
    fun `echo2 should allow echo to access 'log-unlisted-clients' endpoint over https and log it`() {
        // when
        val echo2Response = echoEnvoy.egressOperations.callService("echo2", pathAndQuery = "/log-unlisted-clients")

        // then
        assertThat(echo2Response).isOk().isFrom(echo2LocalService)
        assertThat(echo2Envoy.container.admin().statValue("http.ingress_https.downstream_rq_completed")?.toInt()).isOne()
        assertThat(echo2Envoy.container).hasOneAccessDenialWithActionLog(
            protocol = "https",
            rule = "{\"path\":\"/log-unlisted-clients\",\"pathMatchingType\":\"PATH\",\"methods\":[\"GET\"],\"clients\":[{\"name\":\"authorized-clients\",\"negated\":false}],\"unlistedClientsPolicy\":\"BLOCKANDLOG\"}",
            path = "/log-unlisted-clients",
            method = "GET",
            clientName = "echo",
            clientIp = echoEnvoy.container.ipAddress()
        )
    }

    @Test
    fun `echo should allow source-ip-client to access 'log-unlisted-clients' endpoint over http`() {
        // when
        val echoResponse = sourceIpClientToEchoProxy.call("/log-unlisted-clients")

        // then
        assertThat(echoResponse).isOk().isFrom(echoLocalService)
        assertThat(echoEnvoy.container.admin().statValue("http.ingress_http.downstream_rq_completed")?.toInt()).isOne()
        assertThat(echoEnvoy.container).hasNoRBACDenials()
    }

    @Test
    fun `echo2 should allow source-ip-client to access 'log-unlisted-clients' endpoint over http`() {
        // when
        val echo2Response = sourceIpClientToEcho2Proxy.call("/log-unlisted-clients")

        // then
        assertThat(echo2Response).isOk().isFrom(echo2LocalService)
        assertThat(echo2Envoy.container.admin().statValue("http.ingress_http.downstream_rq_completed")?.toInt()).isOne()
        assertThat(echo2Envoy.container).hasNoRBACDenials()
    }

    @Test
    fun `echo should allow unlisted clients to access 'log-unlisted-clients' endpoint over http and log it`() {
        // when
        val echoResponse = echoEnvoy.ingressOperations.callLocalService("/log-unlisted-clients")

        // then
        assertThat(echoResponse).isOk().isFrom(echoLocalService)
        assertThat(echoEnvoy.container.admin().statValue("http.ingress_http.downstream_rq_completed")?.toInt()).isOne()
        assertThat(echoEnvoy.container).hasOneAccessDenialWithActionLog(
            protocol = "http",
            rule = "{\"path\":\"/log-unlisted-clients\",\"pathMatchingType\":\"PATH\",\"methods\":[\"GET\"],\"clients\":[{\"name\":\"authorized-clients\",\"negated\":false}],\"unlistedClientsPolicy\":\"BLOCKANDLOG\"}",
            path = "/log-unlisted-clients",
            method = "GET",
            clientName = "",
            clientIp = echoEnvoy.container.gatewayIp()
        )
    }

    @Test
    fun `echo2 should allow unlisted clients to access 'log-unlisted-clients' endpoint over http and log it`() {
        // when
        val echo2Response = echo2Envoy.ingressOperations.callLocalService("/log-unlisted-clients")

        // then
        assertThat(echo2Response).isOk().isFrom(echo2LocalService)
        assertThat(echo2Envoy.container.admin().statValue("http.ingress_http.downstream_rq_completed")?.toInt()).isOne()
        assertThat(echo2Envoy.container).hasOneAccessDenialWithActionLog(
            protocol = "http",
            rule = "{\"path\":\"/log-unlisted-clients\",\"pathMatchingType\":\"PATH\",\"methods\":[\"GET\"],\"clients\":[{\"name\":\"authorized-clients\",\"negated\":false}],\"unlistedClientsPolicy\":\"BLOCKANDLOG\"}",
            path = "/log-unlisted-clients",
            method = "GET",
            clientName = "",
            clientIp = echoEnvoy.container.gatewayIp()
        )
    }

    @Test
    fun `echo should allow echo3 to access 'block-unlisted-clients-by-default' endpoint over https`() {
        // when
        val echoResponse =
            echo3Envoy.egressOperations.callService("echo", pathAndQuery = "/block-unlisted-clients-by-default")

        // then
        assertThat(echoResponse).isOk().isFrom(echoLocalService)
        assertThat(echoEnvoy.container.admin().statValue("http.ingress_https.downstream_rq_completed")?.toInt()).isOne()
        assertThat(echoEnvoy.container).hasNoRBACDenials()
    }

    @Test
    fun `echo2 should allow echo3 to access 'block-unlisted-clients-by-default' endpoint over https`() {

        // when
        val echo2Response =
            echo3Envoy.egressOperations.callService("echo2", pathAndQuery = "/block-unlisted-clients-by-default")

        // then
        assertThat(echo2Response).isOk().isFrom(echo2LocalService)
        assertThat(echo2Envoy.container.admin().statValue("http.ingress_https.downstream_rq_completed")?.toInt()).isOne()
        assertThat(echo2Envoy.container).hasNoRBACDenials()
    }

    @Test
    @Tag("flaky")
    fun `echo should NOT allow echo2 to access 'block-unlisted-clients-by-default' endpoint over https`() {
        // when
        val echoResponse =
            echo2Envoy.egressOperations.callService("echo", pathAndQuery = "/block-unlisted-clients-by-default")

        // then
        assertThat(echoResponse).isForbidden()
        assertThat(echoEnvoy.container.admin().statValue("http.ingress_https.downstream_rq_completed")?.toInt()).isOne()
        assertThat(echoEnvoy.container).hasOneAccessDenialWithActionBlock(
            protocol = "https",
            rule = "{\"path\":\"/block-unlisted-clients-by-default\",\"pathMatchingType\":\"PATH\",\"clients\":[{\"name\":\"authorized-clients\",\"negated\":false}]}",
            path = "/block-unlisted-clients-by-default",
            method = "GET",
            clientName = "echo2",
            authority = "echo",
            trustedClient = true,
            clientIp = echo2Envoy.container.ipAddress()
        )
    }

    @Test
    fun `echo2 should NOT allow unlisted clients to access 'block-unlisted-clients-by-default' endpoint over http`() {
        // when
        val echo2Response = echo2Envoy.ingressOperations.callLocalService("/block-unlisted-clients-by-default")

        // then
        assertThat(echo2Response).isForbidden()
        assertThat(echo2Envoy.container.admin().statValue("http.ingress_http.downstream_rq_completed")?.toInt()).isOne()
        assertThat(echo2Envoy.container).hasOneAccessDenialWithActionBlock(
            protocol = "http",
            rule = "{\"path\":\"/block-unlisted-clients-by-default\",\"pathMatchingType\":\"PATH\",\"clients\":[{\"name\":\"authorized-clients\",\"negated\":false}]}",
            path = "/block-unlisted-clients-by-default",
            method = "GET",
            clientName = "",
            authority = echo2Envoy.container.ingressHost(),
            trustedClient = false,
            clientIp = echo2Envoy.container.gatewayIp()
        )
    }

    @Test
    fun `echo should NOT allow echo3 to access unlisted endpoint over https`() {
        // when
        val echoResponse = echo3Envoy.egressOperations.callService("echo", pathAndQuery = "/unlisted-endpoint")

        // then
        assertThat(echoResponse).isForbidden()
        assertThat(echoEnvoy.container.admin().statValue("http.ingress_https.downstream_rq_completed")?.toInt()).isOne()
        assertThat(echoEnvoy.container).hasOneAccessDenialWithActionBlock(
            protocol = "https",
            rule = "?",
            path = "/unlisted-endpoint",
            method = "GET",
            clientName = "echo3",
            authority = "echo",
            trustedClient = true,
            clientIp = echo3Envoy.container.ipAddress()
        )
    }

    @Test
    fun `echo2 should allow echo3 to access unlisted endpoint over https and log it`() {
        // when
        val echo2Response = echo3Envoy.egressOperations.callService("echo2", pathAndQuery = "/unlisted-endpoint")

        // then
        assertThat(echo2Response).isOk().isFrom(echo2LocalService)
        assertThat(echo2Envoy.container.admin().statValue("http.ingress_https.downstream_rq_completed")?.toInt()).isOne()
        assertThat(echo2Envoy.container).hasOneAccessDenialWithActionLog(
            protocol = "https",
            rule = "ALLOW_LOGGED_POLICY",
            path = "/unlisted-endpoint",
            method = "GET",
            clientName = "echo3",
            authority = "echo2",
            trustedClient = true,
            clientIp = echo3Envoy.container.ipAddress()
        )
    }

    @Test
    fun `echo should NOT allow echo2 to access unlisted endpoint over https`() {
        // when
        val echoResponse = echo2Envoy.egressOperations.callService("echo", pathAndQuery = "/unlisted-endpoint")

        // then
        assertThat(echoResponse).isForbidden()
        assertThat(echoEnvoy.container.admin().statValue("http.ingress_https.downstream_rq_completed")?.toInt()).isOne()
        assertThat(echoEnvoy.container).hasOneAccessDenialWithActionBlock(
            protocol = "https",
            rule = "?",
            path = "/unlisted-endpoint",
            method = "GET",
            clientName = "echo2",
            authority = "echo",
            trustedClient = true,
            clientIp = echo2Envoy.container.ipAddress()
        )
    }

    @Test
    @Tag("flaky")
    fun `echo2 should allow echo to access unlisted endpoint over https and log it`() {
        // when
        val echo2Response = echoEnvoy.egressOperations.callService("echo2", pathAndQuery = "/unlisted-endpoint")

        // then
        assertThat(echo2Response).isOk().isFrom(echo2LocalService)
        assertThat(echo2Envoy.container.admin().statValue("http.ingress_https.downstream_rq_completed")?.toInt()).isOne()
        assertThat(echo2Envoy.container).hasOneAccessDenialWithActionLog(
            protocol = "https",
            rule = "ALLOW_LOGGED_POLICY",
            path = "/unlisted-endpoint",
            method = "GET",
            clientName = "echo",
            authority = "echo2",
            trustedClient = true,
            clientIp = echoEnvoy.container.ipAddress()
        )
    }

    @Test
    fun `echo should NOT allow source-ip-client to access unlisted endpoint over http`() {
        // when
        val echoResponse = sourceIpClientToEchoProxy.call("/unlisted-endpoint")

        // then
        assertThat(echoResponse).isForbidden()
        assertThat(echoEnvoy.container.admin().statValue("http.ingress_http.downstream_rq_completed")?.toInt()).isOne()
        assertThat(echoEnvoy.container).hasOneAccessDenialWithActionBlock(
            protocol = "http",
            rule = "?",
            path = "/unlisted-endpoint",
            method = "GET",
            clientName = "",
            authority = sourceIpClientToEchoProxy.address.removePrefix("http://"),
            trustedClient = false,
            clientIp = sourceIpClient.container.ipAddress()
        )
    }

    @Test
    fun `echo2 should allow source-ip-client to access unlisted endpoint over http`() {
        // when
        val echo2Response = sourceIpClientToEcho2Proxy.call("/unlisted-endpoint")

        // then
        assertThat(echo2Response).isOk().isFrom(echo2LocalService)
        assertThat(echo2Envoy.container.admin().statValue("http.ingress_http.downstream_rq_completed")?.toInt()).isOne()
        assertThat(echo2Envoy.container).hasOneAccessDenialWithActionLog(
            protocol = "http",
            rule = "ALLOW_LOGGED_POLICY",
            path = "/unlisted-endpoint",
            method = "GET",
            clientName = "",
            trustedClient = false,
            clientIp = sourceIpClient.container.ipAddress()
        )
    }

    @Test
    fun `echo should NOT allow unlisted clients to access unlisted endpoint over http`() {
        // when
        val echoResponse = echoEnvoy.ingressOperations.callLocalService("/unlisted-endpoint")

        // then
        assertThat(echoResponse).isForbidden()
        assertThat(echoEnvoy.container.admin().statValue("http.ingress_http.downstream_rq_completed")?.toInt()).isOne()
        assertThat(echoEnvoy.container).hasOneAccessDenialWithActionBlock(
            protocol = "http",
            rule = "?",
            path = "/unlisted-endpoint",
            method = "GET",
            clientName = "",
            authority = echoEnvoy.container.ingressHost(),
            trustedClient = false,
            clientIp = echoEnvoy.container.gatewayIp()
        )
    }

    @Test
    fun `echo2 should allow unlisted clients to access unlisted endpoint over http`() {
        // when
        val echo2Response = echo2Envoy.ingressOperations.callLocalService("/unlisted-endpoint")

        // then
        assertThat(echo2Response).isOk().isFrom(echo2LocalService)
        assertThat(echo2Envoy.container.admin().statValue("http.ingress_http.downstream_rq_completed")?.toInt()).isOne()
        assertThat(echo2Envoy.container).hasOneAccessDenialWithActionLog(
            protocol = "http",
            rule = "ALLOW_UNLISTED_POLICY",
            path = "/unlisted-endpoint",
            method = "GET",
            clientName = "",
            trustedClient = false,
            clientIp = echo2Envoy.container.gatewayIp()
        )
    }

    @Test
    fun `echo should NOT allow echo3 to access 'log-unlisted-clients' endpoint with wrong http method`() {
        // when
        val echoResponse = echo3Envoy.egressOperations.callService(
            "echo",
            pathAndQuery = "/log-unlisted-clients",
            method = "POST",
            body = RequestBody.create("application/json".toMediaType(), "{}")
        )

        // then
        assertThat(echoResponse).isForbidden()
        assertThat(echoEnvoy.container.admin().statValue("http.ingress_https.downstream_rq_completed")?.toInt()).isOne()
        assertThat(echoEnvoy.container).hasOneAccessDenialWithActionBlock(
            protocol = "https",
            rule = "{\"path\":\"/log-unlisted-clients\",\"pathMatchingType\":\"PATH\",\"methods\":[\"GET\"],\"clients\":[{\"name\":\"authorized-clients\",\"negated\":false}],\"unlistedClientsPolicy\":\"BLOCKANDLOG\"}",
            path = "/log-unlisted-clients",
            method = "POST",
            clientName = "echo3",
            authority = "echo",
            trustedClient = true,
            clientIp = echo3Envoy.container.ipAddress()
        )
    }

    @Test
    fun `echo2 should allow echo3 to access 'log-unlisted-clients' endpoint with wrong http method and log it`() {
        // when
        val echo2Response = echo3Envoy.egressOperations.callService(
            "echo2",
            pathAndQuery = "/log-unlisted-clients",
            method = "POST",
            body = RequestBody.create("text/plain".toMediaType(), "{}")
        )

        // then
        assertThat(echo2Response).isOk().isFrom(echo2LocalService)
        assertThat(echo2Envoy.container.admin().statValue("http.ingress_https.downstream_rq_completed")?.toInt()).isOne()
        assertThat(echo2Envoy.container).hasOneAccessDenialWithActionLog(
            protocol = "https",
            rule = "{\"path\":\"/log-unlisted-clients\",\"pathMatchingType\":\"PATH\",\"methods\":[\"GET\"],\"clients\":[{\"name\":\"authorized-clients\",\"negated\":false}],\"unlistedClientsPolicy\":\"BLOCKANDLOG\"}",
            path = "/log-unlisted-clients",
            method = "POST",
            clientName = "echo3",
            trustedClient = true,
            clientIp = echo3Envoy.container.ipAddress()
        )
    }

    @Test
    fun `echo2 should allow unlisted client with client identity header over https and log client name as untrusted`() {

        // when
        val echo2Response = echo2Envoy.ingressOperations.callLocalServiceInsecure(
            endpoint = "/log-unlisted-clients",
            headers = mapOf("x-service-name" to "service-name-from-header").toHeaders(),
            useTls = true
        )

        // then
        assertThat(echo2Response).isOk().isFrom(echo2LocalService)
        assertThat(echo2Envoy.container.admin().statValue("http.ingress_https.downstream_rq_completed")?.toInt()).isOne()
        assertThat(echo2Envoy.container).hasOneAccessDenialWithActionLog(
            protocol = "https",
            rule = "{\"path\":\"/log-unlisted-clients\",\"pathMatchingType\":\"PATH\",\"methods\":[\"GET\"],\"clients\":[{\"name\":\"authorized-clients\",\"negated\":false}],\"unlistedClientsPolicy\":\"BLOCKANDLOG\"}",
            path = "/log-unlisted-clients",
            method = "GET",
            clientName = "service-name-from-header (not trusted)",
            authority = echo2Envoy.container.ingressHost(),
            trustedClient = false,
            clientIp = echo2Envoy.container.gatewayIp()
        )
    }

    @Test
    fun `echo2 should NOT allow echo to access 'block-unlisted-clients-by-default' endpoint over https`() {
        // when
        val echo2Response =
            echoEnvoy.egressOperations.callService("echo2", pathAndQuery = "/block-unlisted-clients-by-default")

        // then
        assertThat(echo2Response).isForbidden()
        assertThat(echo2Envoy.container.admin().statValue("http.ingress_https.downstream_rq_completed")?.toInt()).isOne()
        assertThat(echo2Envoy.container).hasOneAccessDenialWithActionBlock(
            protocol = "https",
            rule = "{\"path\":\"/block-unlisted-clients-by-default\",\"pathMatchingType\":\"PATH\",\"clients\":[{\"name\":\"authorized-clients\",\"negated\":false}]}",
            path = "/block-unlisted-clients-by-default",
            method = "GET",
            clientName = "echo",
            authority = "echo2",
            trustedClient = true,
            clientIp = echoEnvoy.container.ipAddress()
        )
    }
}
