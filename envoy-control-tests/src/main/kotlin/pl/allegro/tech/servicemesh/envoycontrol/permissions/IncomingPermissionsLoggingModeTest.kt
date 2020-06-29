package pl.allegro.tech.servicemesh.envoycontrol

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo1EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo2EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlRunnerTestApp
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration
import pl.allegro.tech.servicemesh.envoycontrol.config.containers.ToxiproxyContainer

internal class IncomingPermissionsLoggingModeTest : EnvoyControlTestConfiguration() {

    companion object {
        private const val prefix = "envoy-control.envoy.snapshot"
        private val properties = { sourceClientIp: String -> mapOf(
            "$prefix.incoming-permissions.enabled" to true,
            "$prefix.incoming-permissions.source-ip-authentication.ip-from-range.source-ip-client" to
                "$sourceClientIp/32",
            "$prefix.routes.status.create-virtual-cluster" to true,
            "$prefix.routes.status.path-prefix" to "/status/", // TODO: testy na status: powinno być dostępne po http
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
        """.trimIndent()

        private val echoConfig = Echo1EnvoyAuthConfig.copy(
            configOverride = proxySettings(unlistedEndpointsPolicy = "blockAndLog"))
        private val echo2Config = Echo2EnvoyAuthConfig.copy(
            configOverride = proxySettings(unlistedEndpointsPolicy = "log"))

        val echoEnvoy by lazy { envoyContainer1 }
        val echo2Envoy by lazy { envoyContainer2 }

        @Container
        // language=yaml
        private val echo3envoy = createEnvoyContainerWithEcho3Certificate(configOverride = """
            node:
              metadata:
                proxy_settings:
                  outgoing:
                    dependencies:
                      - service: "echo"
                      - service: "echo2"
        """.trimIndent())

        @Container
        private val sourceIpClient = ToxiproxyContainer(exposedPortsCount = 1)

        @JvmStatic
        @BeforeAll
        fun setupTest() {
            setup(appFactoryForEc1 = { consulPort ->
                EnvoyControlRunnerTestApp(properties = properties(sourceIpClient.ipAddress()), consulPort = consulPort) },
                envoys = 2,
                envoyConfig = echoConfig,
                secondEnvoyConfig = echo2Config
            )
            registerService(name = "echo", tags = listOf("mtls:enabled"))
            registerService(name = "echo2", tags = listOf("mtls:enabled"))
        }
    }

    @Test
    fun `should allow echo3 to access 'block-unlisted-clients' endpoint over https`() {
        // when

        call

    }
}
