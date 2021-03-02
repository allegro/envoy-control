package pl.allegro.tech.servicemesh.envoycontrol.permissions

import okhttp3.Headers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.hasOneAccessAllowedWithActionLog
import pl.allegro.tech.servicemesh.envoycontrol.assertions.hasOneAccessDenialWithActionBlock
import pl.allegro.tech.servicemesh.envoycontrol.assertions.hasOneAccessDenialWithActionLog
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isForbidden
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isFrom
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isOk
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo1EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo2EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo3EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyContainer
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.EndpointMatch

internal class IncomingPermissionsAllowedClientTest {

    companion object {
        private const val prefix = "envoy-control.envoy.snapshot"
        private val properties = mapOf(
            "$prefix.incoming-permissions.enabled" to true,
            "$prefix.incoming-permissions.overlapping-paths-fix" to true,
            "$prefix.routes.status.create-virtual-cluster" to true,
            "$prefix.routes.status.endpoints" to mutableListOf(EndpointMatch().also { it.path = "/status/" }),
            "$prefix.routes.status.enabled" to true,
            "$prefix.incoming-permissions.clients-allowed-to-all-endpoints" to listOf("echo3")
        )

        // language=yaml
        private fun proxySettings(unlistedEndpointsPolicy: String) = """
            node: 
              metadata: 
                proxy_settings: 
                  incoming:
                    unlistedEndpointsPolicy: $unlistedEndpointsPolicy
                    endpoints:
                    - path: "/block-unlisted-clients"
                      clients: ["echo"]
                      unlistedClientsPolicy: blockAndLog
                    - path: "/log-unlisted-clients"
                      methods: [GET]
                      clients: ["echo"]
                      unlistedClientsPolicy: log
                  outgoing:
                    dependencies: []
        """.trimIndent()

        // language=yaml
        private val echo3Yaml = """
            node:
              metadata:
                proxy_settings:
                  outgoing:
                    dependencies:
                      - service: "echo"
                      - service: "echo2"
        """.trimIndent()

        private val echoConfig = Echo1EnvoyAuthConfig.copy(
            configOverride = proxySettings(unlistedEndpointsPolicy = "blockAndLog")
        )

        private val echo2Config = Echo2EnvoyAuthConfig.copy(
            configOverride = proxySettings(unlistedEndpointsPolicy = "log")
        )

        private val envoy3Config = Echo3EnvoyAuthConfig.copy(configOverride = echo3Yaml)

        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(consul, properties)

        @JvmField
        @RegisterExtension
        val service = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val echoEnvoy = EnvoyExtension(envoyControl, localService = service, config = echoConfig)

        @JvmField
        @RegisterExtension
        val echo2Envoy = EnvoyExtension(envoyControl, localService = service, config = echo2Config)

        @JvmField
        @RegisterExtension
        val envoy3 = EnvoyExtension(envoyControl, config = envoy3Config)
    }

    @Test
    fun `echo should allow special client with name from the certificate to access endpoint and log it when policy log`() {
        // given
        consul.server.operations.registerServiceWithEnvoyOnIngress(
            name = "echo",
            extension = echoEnvoy,
            tags = listOf("mtls:enabled")
        )
        envoy3.waitForAvailableEndpoints("echo")

        // when
        val echoResponse = envoy3.egressOperations.callService(
            service = "echo",
            pathAndQuery = "/log-unlisted-clients",
            headers = mapOf("x-service-name" to "allowed-client")
        )

        // then
        assertThat(echoResponse).isOk().isFrom(service)
        assertThat(echoEnvoy.container).hasOneAccessAllowedWithActionLog(
            protocol = "https",
            path = "/log-unlisted-clients",
            method = "GET",
            clientName = "echo3",
            trustedClient = true,
            clientAllowedToAllEndpoints = true,
            clientIp = envoy3.container.ipAddress()
        )
    }

    @Test
    fun `echo should allow special client with name from the certificate to access endpoint and log it when policy blockAndLog`() {
        // given
        consul.server.operations.registerServiceWithEnvoyOnIngress(
            name = "echo",
            extension = echoEnvoy,
            tags = listOf("mtls:enabled")
        )
        envoy3.waitForAvailableEndpoints("echo")

        // when
        val echoResponse = envoy3.egressOperations.callService(
            service = "echo",
            pathAndQuery = "/block-unlisted-clients"
        )

        // then
        assertThat(echoResponse).isOk().isFrom(service)
        assertThat(echoEnvoy.container).hasOneAccessAllowedWithActionLog(
            protocol = "https",
            path = "/block-unlisted-clients",
            method = "GET",
            clientName = "echo3",
            trustedClient = true,
            clientAllowedToAllEndpoints = true,
            clientIp = envoy3.container.ipAddress()
        )
    }

    @Test
    fun `echo should allow special client with name from header to access endpoint and log it when policy log`() {
        // given
        consul.server.operations.registerServiceWithEnvoyOnIngress(
            name = "echo",
            extension = echoEnvoy,
            tags = listOf("mtls:enabled")
        )
        envoy3.waitForAvailableEndpoints("echo")

        // when
        val echoResponse = echoEnvoy.ingressOperations.callLocalService(
            endpoint = "/log-unlisted-clients",
            headers = Headers.of(mapOf("x-service-name" to "echo3"))
        )

        // then
        assertThat(echoResponse).isOk().isFrom(service)
        assertThat(echoEnvoy.container).hasOneAccessDenialWithActionLog(
            protocol = "http",
            path = "/log-unlisted-clients",
            method = "GET",
            clientName = "echo3",
            trustedClient = false,
            clientAllowedToAllEndpoints = true,
            clientIp = echoEnvoy.container.gatewayIp()
        )
    }

    @Test
    fun `echo should block special client with name from header to access endpoint and log it when policy blockAndLog`() {
        // given
        consul.server.operations.registerServiceWithEnvoyOnIngress(
            name = "echo",
            extension = echoEnvoy,
            tags = listOf("mtls:enabled")
        )
        envoy3.waitForAvailableEndpoints("echo")

        // when
        val echoResponse = echoEnvoy.ingressOperations.callLocalService(
            endpoint = "/block-unlisted-clients",
            headers = Headers.of(mapOf("x-service-name" to "echo3"))
        )

        // then
        assertThat(echoResponse).isForbidden()
        assertThat(echoEnvoy.container).hasOneAccessDenialWithActionBlock(
            protocol = "http",
            path = "/block-unlisted-clients",
            method = "GET",
            clientName = "echo3",
            trustedClient = false,
            clientAllowedToAllEndpoints = true,
            clientIp = echoEnvoy.container.gatewayIp()
        )
    }

    @Test
    fun `echo2 should allow special client with name from header over https and log request when unlistedEndpointsPolicy is log`() {
        // when
        val echo2Response = echo2Envoy.ingressOperations.callLocalServiceInsecure(
            endpoint = "/log-unlisted-endpoint",
            headers = Headers.of(mapOf("x-service-name" to "echo3")),
            useTls = true
        )

        // then
        assertThat(echo2Response).isOk().isFrom(service)
        assertThat(echo2Envoy.container.ingressTlsRequests()).isOne()
        assertThat(echo2Envoy.container).hasOneAccessDenialWithActionLog(
            protocol = "https",
            path = "/log-unlisted-endpoint",
            method = "GET",
            clientName = "echo3",
            trustedClient = false,
            clientAllowedToAllEndpoints = true,
            clientIp = echo2Envoy.container.gatewayIp()
        )
    }

    @Test
    fun `echo should block special client with name from header over https and log request when unlistedEndpointsPolicy is blockAndLog`() {
        // when
        val echoResponse = echoEnvoy.ingressOperations.callLocalServiceInsecure(
            endpoint = "/block-and-log-unlisted-endpoint",
            headers = Headers.of(mapOf("x-service-name" to "echo3")),
            useTls = true
        )

        // then
        assertThat(echoResponse).isForbidden()
        assertThat(echoEnvoy.container.ingressTlsRequests()).isOne()
        assertThat(echoEnvoy.container).hasOneAccessDenialWithActionBlock(
            protocol = "https",
            path = "/block-and-log-unlisted-endpoint",
            method = "GET",
            clientName = "echo3",
            trustedClient = false,
            clientAllowedToAllEndpoints = true,
            clientIp = echoEnvoy.container.gatewayIp()
        )
    }

    @BeforeEach
    fun startRecordingRBACLogs() {
        echoEnvoy.recordRBACLogs()
        echo2Envoy.recordRBACLogs()
    }

    @AfterEach
    fun cleanupTest() {
        echoEnvoy.container.admin().resetCounters()
        echoEnvoy.container.logRecorder.stopRecording()
        echo2Envoy.container.admin().resetCounters()
        echo2Envoy.container.logRecorder.stopRecording()
    }

    private fun EnvoyContainer.ingressTlsRequests() =
        this.admin().statValue("http.ingress_https.downstream_rq_completed")?.toInt()
}
