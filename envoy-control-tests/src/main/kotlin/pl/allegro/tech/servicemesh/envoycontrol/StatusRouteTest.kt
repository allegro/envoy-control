package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlRunnerTestApp
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.EndpointMatch

class StatusRouteTest : EnvoyControlTestConfiguration() {
    companion object {
        private val properties = mapOf(
                "envoy-control.envoy.snapshot.routes.status.enabled" to true,
                "envoy-control.envoy.snapshot.routes.status.endpoints" to mutableListOf(EndpointMatch().also { it.path = "/my-status/" }),
                "envoy-control.envoy.snapshot.routes.status.createVirtualCluster" to true
        )

        @JvmStatic
        @BeforeAll
        fun setupTest() {
            setup(appFactoryForEc1 = { consulPort -> EnvoyControlRunnerTestApp(properties, consulPort) })
        }
    }

    @Test
    fun `should allow defining custom status prefix`() {
        untilAsserted {
            // when
            val ingressRoot = callEnvoyIngress(path = "/my-status/abc")

            // then
            val statusUpstreamOk = envoyContainer1.admin().statValue(
                    "vhost.secured_local_service.vcluster.status.upstream_rq_200"
            )?.toInt()
            assertThat(statusUpstreamOk).isGreaterThan(0)
            assertThat(ingressRoot.code()).isEqualTo(200)
        }
    }
}
