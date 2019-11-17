package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Duration.FIVE_SECONDS
import org.awaitility.Duration.TEN_SECONDS
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlRunnerTestApp
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyContainer
import java.time.Duration

internal class HttpIdleTimeoutTest : EnvoyControlTestConfiguration() {

    companion object {

        private val properties = mapOf(
            "envoy-control.envoy.snapshot.egress.commonHttp.idleTimeout" to Duration.ofSeconds(1)
        )

        @JvmStatic
        @BeforeAll
        fun setupTest() {
            setup(appFactoryForEc1 = { consulPort -> EnvoyControlRunnerTestApp(properties, consulPort) }, envoys = 2)
        }
    }

    @Test
    fun `should close idle connections after 1s for HTTP2`() {
        // given
        registerService(name = "proxy1", port = EnvoyContainer.INGRESS_LISTENER_CONTAINER_PORT, container = envoyContainer2, tags = listOf("envoy"))

        // when
        untilAsserted {
            callProxy()
        }

        // then
        untilAsserted {
            assertHasActiveConnection(envoyContainer1)
            callProxy()
        }

        // 5 seconds drain time + 1 idle + 4 padding
        untilAsserted(wait = TEN_SECONDS) {
            assertHasNoActiveConnections(envoyContainer1)
        }
    }

    @Test
    fun `should close idle connections after 1s for HTTP1`() {
        // given
        registerService(name = "proxy1", port = EnvoyContainer.INGRESS_LISTENER_CONTAINER_PORT, container = envoyContainer2)

        // when
        untilAsserted {
            callProxy()
        }

        // then
        untilAsserted {
            assertHasActiveConnection(envoyContainer1)
            callProxy()
        }

        // 1 idle + 4 padding
        untilAsserted(wait = FIVE_SECONDS) {
            assertHasNoActiveConnections(envoyContainer1)
        }
    }

    private fun callProxy() {
        val response = callService("proxy1")
        assertThat(response).isOk()
    }

    private fun assertHasActiveConnection(container: EnvoyContainer) {
        val http2Connections = container.admin().statValue("cluster.proxy1.upstream_cx_active")?.toInt()
        assertThat(http2Connections).isGreaterThan(0)
    }

    private fun assertHasNoActiveConnections(container: EnvoyContainer) {
        val http2Connections = container.admin().statValue("cluster.proxy1.upstream_cx_active")?.toInt()
        assertThat(http2Connections).isEqualTo(0)
    }
}
