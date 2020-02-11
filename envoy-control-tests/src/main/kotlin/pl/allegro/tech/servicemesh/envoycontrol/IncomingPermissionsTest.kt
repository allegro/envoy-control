package pl.allegro.tech.servicemesh.envoycontrol

import okhttp3.Headers
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.config.Ads
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlRunnerTestApp
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration
import java.util.concurrent.TimeUnit

internal class IncomingPermissionsTest : EnvoyControlTestConfiguration() {

    companion object {

        private val properties = mapOf(
            "envoy-control.envoy.snapshot.incoming-permissions.enabled" to true
        )

        @JvmStatic
        @BeforeAll
        fun setupTest() {
            setup(appFactoryForEc1 = { consulPort ->
                EnvoyControlRunnerTestApp(properties = properties, consulPort = consulPort)
            }, envoyConfig = Ads)
        }
    }

    @Test
    fun `should allow access to endpoint by authorized client`() {
        registerService(name = "echo")

        await().pollThread {
            Thread(it)
        }.atMost(180, TimeUnit.MINUTES).pollInterval(179, TimeUnit.MINUTES).until { false }


        val response = callLocalService(endpoint = "/endpoint",
            headers = Headers.of(mapOf("x-service-name" to "echo")))
    }

    @Test
    fun `should deny access to endpoint by unauthorized client`() {
        untilAsserted {
            // when
            val response = callLocalService(endpoint = "/endpoint",
                headers = Headers.of(mapOf("x-service-name" to "unuthorizedClient")))

            // then
            assertThat(response).isForbidden()
        }
    }
}
