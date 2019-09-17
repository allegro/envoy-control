package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlRunnerTestApp
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration

internal class IncomingPermissionsTest : EnvoyControlTestConfiguration() {

    companion object {

        private val properties = mapOf(
            "envoy-control.envoy.snapshot.incoming-permissions.enabled" to true,
            "envoy-control.envoy.snapshot.incoming-permissions.endpoint-unavailable-status-code" to 403
        )

        @JvmStatic
        @BeforeAll
        fun setupTest() {
            setup(appFactoryForEc1 = { consulPort ->
                EnvoyControlRunnerTestApp(properties = properties, consulPort = consulPort)
            })
        }
    }

    @Test
    fun `should allow access to endpoint by authorized client`() {
        untilAsserted {
            // when
            val response = callLocalService(endpoint = "/endpoint", clientServiceName = "authorizedClient")

            // then
            assertThat(response).isOk().isFrom(localServiceContainer)
        }
    }

    @Test
    fun `should deny access to endpoint by unauthorized client`() {
        untilAsserted {
            // when
            val response = callLocalService(endpoint = "/endpoint", clientServiceName = "unauthorizedClient")

            // then
            assertThat(response).isForbidden()
        }
    }
}
