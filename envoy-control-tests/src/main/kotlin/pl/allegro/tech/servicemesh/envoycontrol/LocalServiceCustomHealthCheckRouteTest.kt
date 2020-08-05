package pl.allegro.tech.servicemesh.envoycontrol

import okhttp3.Headers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.AdsCustomHealthCheck
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension

class LocalServiceCustomHealthCheckRouteTest {

    companion object {

        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(consul)

        @JvmField
        @RegisterExtension
        val service = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val envoy = EnvoyExtension(envoyControl, service, AdsCustomHealthCheck)
    }

    @Test
    fun `should health check be routed to custom cluster`() {
        untilAsserted {
            // when
            envoy.ingressOperations.callLocalService(endpoint = "/status/custom", headers = Headers.of())

            // then
            assertThat(envoy.container.admin().statValue("cluster.local_service_health_check.upstream_rq_200")?.toInt()).isGreaterThan(0)
            assertThat(envoy.container.admin().statValue("cluster.local_service.upstream_rq_200")?.toInt()).isEqualTo(-1)
        }

        // and
        envoy.ingressOperations.callLocalService(endpoint = "/status/ping", headers = Headers.of())

        // then
        assertThat(envoy.container.admin().statValue("cluster.local_service.upstream_rq_200")?.toInt()).isEqualTo(1)
    }
}
