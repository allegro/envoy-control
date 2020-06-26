package pl.allegro.tech.servicemesh.envoycontrol.permissions

import okhttp3.Headers
import okhttp3.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo1EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo2EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlRunnerTestApp
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyContainer

internal class SourceIpBasedAuthenticationTest : EnvoyControlTestConfiguration() {

    companion object {
        private const val prefix = "envoy-control.envoy.snapshot"
        private val properties = mapOf(
            "$prefix.incoming-permissions.enabled" to true,
            "$prefix.incoming-permissions.source-ip-authentication.ip-from-service-discovery.enabled-for-incoming-services" to
                    listOf("echo"),
            "$prefix.routes.status.create-virtual-cluster" to true,
            "$prefix.routes.status.path-prefix" to "/status/",
            "$prefix.routes.status.enabled" to true
        )

        private val echo2EnvoyConfig = Echo2EnvoyAuthConfig.copy(configOverride = """
            node:
              metadata:
                proxy_settings:
                  incoming:
                    endpoints:
                      - path: "/secured_endpoint"
                        clients: ["echo"]
        """.trimIndent())

        @JvmStatic
        @BeforeAll
        fun setupTest() {
            setup(appFactoryForEc1 = { consulPort ->
                EnvoyControlRunnerTestApp(properties = properties, consulPort = consulPort)
            }, envoyConfig = Echo1EnvoyAuthConfig, secondEnvoyConfig = echo2EnvoyConfig, envoys = 2)
        }
    }

    @Test
    fun `should allow access to selected clients using source based authentication`() {
        registerEcho1WithEnvoy1OnIngress()
        registerEcho2WithEnvoy2OnIngress()

        untilAsserted {
            // when
            val validResponse = callEcho2ThroughEnvoy1()
            val invalidResponse = callEcho2ThroughEnvoy2Ingress()

            // then
            assertThat(validResponse).isOk().isFrom(echoContainer2)
            assertThat(invalidResponse).isForbidden()
        }
    }

    private fun registerEcho1WithEnvoy1OnIngress() {
        registerService(
                id = "echo",
                name = "echo", address = envoyContainer1.ipAddress(),
                port = EnvoyContainer.INGRESS_LISTENER_CONTAINER_PORT
        )
    }

    private fun registerEcho2WithEnvoy2OnIngress() {
        registerService(
                id = "echo2",
                name = "echo2", address = envoyContainer2.ipAddress(),
                port = EnvoyContainer.INGRESS_LISTENER_CONTAINER_PORT
        )
    }

    private fun callEcho2ThroughEnvoy2Ingress(): Response {
        return callLocalService("", Headers.of(), envoyContainer2)
    }

    private fun callEcho2ThroughEnvoy1() = callService(service = "echo2", pathAndQuery = "/secured_endpoint")
}
