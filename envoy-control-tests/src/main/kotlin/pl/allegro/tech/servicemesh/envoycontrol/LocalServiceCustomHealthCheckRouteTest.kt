package pl.allegro.tech.servicemesh.envoycontrol

import okhttp3.Headers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.config.AdsCustomHealthCheck
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlRunnerTestApp
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration

internal class LocalServiceCustomHealthCheckRouteTest : EnvoyControlTestConfiguration() {

    companion object {

        @JvmStatic
        @BeforeAll
        fun setupTest() {
            setup(
                envoyConfig = AdsCustomHealthCheck,
                appFactoryForEc1 = { consulPort -> EnvoyControlRunnerTestApp(emptyMap(), consulPort) }
            )
        }
    }

    @Test
    fun `should health check be routed to custom cluster`() {
        untilAsserted {
            // when
            callLocalService(endpoint = "/status/custom", headers = Headers.of())

            // then
            assertThat(envoyContainer1.admin().statValue("cluster.local_service_health_check.upstream_rq_200")?.toInt()).isGreaterThan(0)
            assertThat(envoyContainer1.admin().statValue("cluster.local_service.upstream_rq_200")?.toInt()).isEqualTo(-1)
        }

        // and
        callLocalService(endpoint = "/status/ping", headers = Headers.of())

        // then
        assertThat(envoyContainer1.admin().statValue("cluster.local_service.upstream_rq_200")?.toInt()).isEqualTo(1)
    }
}
