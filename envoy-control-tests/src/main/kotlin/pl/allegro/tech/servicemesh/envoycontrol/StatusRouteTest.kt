package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlRunnerTestApp
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration

class StatusRouteTest : EnvoyControlTestConfiguration() {
    companion object {
        private val properties = mapOf(
                "envoy-control.envoy.snapshot.routes.status.enabled" to true,
                "envoy-control.envoy.snapshot.routes.status.pathPrefix" to "/my-status/",
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
            val ingressRoot = callEnvoyIngress("/my-status")

            // then
            assertThat(ingressRoot.code()).isEqualTo(200)
        }
    }
}
