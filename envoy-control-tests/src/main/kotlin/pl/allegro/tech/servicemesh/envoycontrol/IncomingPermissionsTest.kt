package pl.allegro.tech.servicemesh.envoycontrol

import okhttp3.Headers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlRunnerTestApp
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration

internal class IncomingPermissionsTest : EnvoyControlTestConfiguration() {

    companion object {

        private val properties = mapOf(
            "envoy-control.envoy.snapshot.incoming-permissions.enabled" to true,
            "envoy-control.envoy.snapshot.routes.status.create-virtual-cluster" to true,
            "envoy-control.envoy.snapshot.routes.status.path-prefix" to "/status/",
            "envoy-control.envoy.snapshot.routes.status.enabled" to true
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
    fun `should allow access to status endpoint by all clients`() {
        untilAsserted {
            // when
            val response = callLocalService(endpoint = "/status/", headers = Headers.of())
            val statusUpstreamOk = envoyContainer1.admin().statValue(
                    "vhost.secured_local_service.vcluster.status.upstream_rq_200"
            )?.toInt()

            // then
            assertThat(response).isOk().isFrom(localServiceContainer)
            assertThat(statusUpstreamOk).isGreaterThan(0)
        }
    }

    @Test
    fun `should allow access to endpoint by authorized client`() {
        untilAsserted {
            // when
            val response = callLocalService(endpoint = "/endpoint?a=b",
                headers = Headers.of(mapOf("x-service-name" to "authorizedClient")))

            // then
            assertThat(response).isOk().isFrom(localServiceContainer)
        }
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
