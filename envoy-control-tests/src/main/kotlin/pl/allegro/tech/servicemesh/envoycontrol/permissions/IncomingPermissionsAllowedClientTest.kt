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
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
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
            "$prefix.incoming-permissions.clients-allowed-to-all-endpoints" to listOf("echo2")
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
        private val echo2Yaml = """
            node:
              metadata:
                proxy_settings:
                  outgoing:
                    dependencies:
                      - service: "echo"
        """.trimIndent()

        private val echoConfig = Echo1EnvoyAuthConfig.copy(
            configOverride = proxySettings(unlistedEndpointsPolicy = "blockAndLog")
        )

        private val envoy2Config = Echo2EnvoyAuthConfig.copy(configOverride = echo2Yaml)

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
        val envoy2 = EnvoyExtension(envoyControl, config = envoy2Config)
    }

    @Test
    fun `echo should allow special client with name from the certificate to access endpoint and log it when policy log`() {
        // given
        consul.server.operations.registerServiceWithEnvoyOnIngress(
            name = "echo",
            extension = echoEnvoy,
            tags = listOf("mtls:enabled")
        )
        envoy2.waitForAvailableEndpoints("echo")

        // when
        val echoResponse = envoy2.egressOperations.callService(
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
            clientName = "echo2",
            trustedClient = true,
            clientAllowedToAllEndpoints = true,
            clientIp = envoy2.container.ipAddress()
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
        envoy2.waitForAvailableEndpoints("echo")

        // when
        val echoResponse = envoy2.egressOperations.callService(
            service = "echo",
            pathAndQuery = "/block-unlisted-clients"
        )

        // then
        assertThat(echoResponse).isOk().isFrom(service)
        assertThat(echoEnvoy.container).hasOneAccessAllowedWithActionLog(
            protocol = "https",
            path = "/block-unlisted-clients",
            method = "GET",
            clientName = "echo2",
            trustedClient = true,
            clientAllowedToAllEndpoints = true,
            clientIp = envoy2.container.ipAddress()
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
        envoy2.waitForAvailableEndpoints("echo")

        // when
        val echoResponse = echoEnvoy.ingressOperations.callLocalService(
            endpoint = "/log-unlisted-clients",
            headers = Headers.of(mapOf("x-service-name" to "echo2"))
        )

        // then
        assertThat(echoResponse).isOk().isFrom(service)
        assertThat(echoEnvoy.container).hasOneAccessDenialWithActionLog(
            protocol = "http",
            path = "/log-unlisted-clients",
            method = "GET",
            clientName = "echo2",
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
        envoy2.waitForAvailableEndpoints("echo")

        // when
        val echoResponse = echoEnvoy.ingressOperations.callLocalService(
            endpoint = "/block-unlisted-clients",
            headers = Headers.of(mapOf("x-service-name" to "echo2"))
        )

        // then
        assertThat(echoResponse).isForbidden()
        assertThat(echoEnvoy.container).hasOneAccessDenialWithActionBlock(
            protocol = "http",
            path = "/block-unlisted-clients",
            method = "GET",
            clientName = "echo2",
            trustedClient = false,
            clientAllowedToAllEndpoints = true,
            clientIp = echoEnvoy.container.gatewayIp()
        )
    }

    @BeforeEach
    fun startRecordingRBACLogs() {
        echoEnvoy.recordRBACLogs()
    }

    @AfterEach
    fun cleanupTest() {
        echoEnvoy.container.admin().resetCounters()
        echoEnvoy.container.logRecorder.stopRecording()
    }
}
