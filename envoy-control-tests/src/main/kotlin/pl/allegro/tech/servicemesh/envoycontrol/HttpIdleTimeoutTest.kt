package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions.assertThat
import java.time.Duration
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlRunnerTestApp
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyContainer

internal class HttpIdleTimeoutTest : EnvoyControlTestConfiguration() {
    companion object {

        private val properties = mapOf(
            "envoy-control.envoy.snapshot.egress.commonHttp.idleTimeout" to Duration.ofSeconds(10)
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
        val name = "proxy1"
        registerService(name = "proxy1", port = EnvoyContainer.INGRESS_LISTENER_CONTAINER_PORT, container = envoyContainer2, tags = listOf("envoy"))

        // when
        untilAsserted {
            callProxy(name)
        }

        // then
        untilAsserted {
            assertHasActiveConnection(envoyContainer1, name)
        }

        // 5 seconds drain time + 10 idle + 5 padding
        untilAsserted(wait = Duration.ofSeconds(20)) {
            assertHasNoActiveConnections(envoyContainer1, name)
        }
    }

    @Test
    fun `should close idle connections after 1s for HTTP1`() {
        // given
        val name = "proxy2"
        registerService(name = name, port = EnvoyContainer.INGRESS_LISTENER_CONTAINER_PORT, container = envoyContainer2)

        // when
        untilAsserted {
            callProxy(name)
        }

        // then
        untilAsserted {
            assertHasActiveConnection(envoyContainer1, name)
        }

        // 10 idle + 5 padding
        untilAsserted(wait = Duration.ofSeconds(15)) {
            assertHasNoActiveConnections(envoyContainer1, name)
        }
    }

    private fun callProxy(name: String) {
        val response = callService(name)
        assertThat(response).isOk()
    }

    private fun assertHasActiveConnection(
        container: EnvoyContainer,
        name: String
    ) {
        val activeConnections = container.admin().statValue("cluster.$name.upstream_cx_active")?.toInt()
        assertThat(activeConnections).isEqualTo(1)
    }

    private fun assertHasNoActiveConnections(container: EnvoyContainer, name: String) {
        val activeConnections = container.admin().statValue("cluster.$name.upstream_cx_active")?.toInt()
        assertThat(activeConnections).isEqualTo(0)
    }
}
