package pl.allegro.tech.servicemesh.envoycontrol

import okhttp3.Headers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.config.AdsWithDisabledEndpointPermissions
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlRunnerTestApp
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration

internal class IncomingPermissionsDisabledInClientTest : EnvoyControlTestConfiguration() {

    companion object {

        private val properties = mapOf(
            "envoy-control.envoy.snapshot.incoming-permissions.enabled" to true
        )

        @JvmStatic
        @BeforeAll
        fun setupTest() {
            setup(appFactoryForEc1 = { consulPort ->
                EnvoyControlRunnerTestApp(properties = properties, consulPort = consulPort)
            }, envoyConfig = AdsWithDisabledEndpointPermissions)
        }
    }

    @Test
    fun `should allow access to endpoint by any client when endpoint permissions disabled`() {
        untilAsserted {
            // when
            val response = callLocalService(endpoint = "/",
                headers = Headers.of(mapOf("x-service-name" to "any")))

            // then
            assertThat(response).isOk().isFrom(localServiceContainer)
        }
    }
}
