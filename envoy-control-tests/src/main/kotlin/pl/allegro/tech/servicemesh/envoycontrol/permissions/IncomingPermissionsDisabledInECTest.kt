package pl.allegro.tech.servicemesh.envoycontrol.permissions

import okhttp3.Headers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlRunnerTestApp
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration

internal class IncomingPermissionsDisabledInECTest : EnvoyControlTestConfiguration() {

    companion object {

        private val properties = mapOf(
            "envoy-control.envoy.snapshot.incoming-permissions.enabled" to false
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
            val response = callLocalService(endpoint = "/endpoint",
                headers = Headers.of(mapOf("x-service-name" to "authorizedClient")))

            // then
            assertThat(response).isOk().isFrom(localServiceContainer)
        }
    }

    @Test
    fun `should allow access to endpoint by unauthorized client when endpoint permissions disabled`() {
        untilAsserted {
            // when
            val response = callLocalService(endpoint = "/endpoint",
                headers = Headers.of(mapOf("x-service-name" to "unuthorizedClient")))

            // then
            assertThat(response).isOk().isFrom(localServiceContainer)
        }
    }
}
