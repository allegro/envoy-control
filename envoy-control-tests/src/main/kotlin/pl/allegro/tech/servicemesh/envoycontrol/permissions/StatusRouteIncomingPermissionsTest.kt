package pl.allegro.tech.servicemesh.envoycontrol.permissions

import okhttp3.Headers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlRunnerTestApp
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.EndpointMatch

internal class StatusRouteIncomingPermissionsTest : EnvoyControlTestConfiguration() {

    companion object {

        private val properties = mapOf(
            "envoy-control.envoy.snapshot.incoming-permissions.enabled" to true,
            "envoy-control.envoy.snapshot.incoming-permissions.overlapping-paths-fix" to true,
            "envoy-control.envoy.snapshot.routes.status.create-virtual-cluster" to true,
            "envoy-control.envoy.snapshot.routes.status.endpoints" to mutableListOf(EndpointMatch().also { it.path = "/status/" }),
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
            val response = callLocalService("/status/", Headers.of())
            val statusUpstreamOk = envoyContainer1.admin().statValue(
                    "vhost.secured_local_service.vcluster.status.upstream_rq_200"
            )?.toInt()

            // then
            assertThat(response).isOk().isFrom(localServiceContainer)
            assertThat(statusUpstreamOk).isGreaterThan(0)
        }
    }
}
