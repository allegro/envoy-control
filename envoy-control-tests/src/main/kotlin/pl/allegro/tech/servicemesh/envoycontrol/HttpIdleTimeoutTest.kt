package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Duration
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlRunnerTestApp
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyContainer
import java.util.concurrent.TimeUnit

internal class HttpIdleTimeoutTest : EnvoyControlTestConfiguration() {
    private val logger by logger()
    companion object {

        private val properties = mapOf(
            "envoy-control.envoy.snapshot.egress.commonHttp.idleTimeout" to java.time.Duration.ofSeconds(10)
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
            val stats = envoyContainer1.admin().allStats("cluster.proxy1.")
            logger.warn(stats)
            assertHasOrHadActiveConnection(envoyContainer1, "http2")
        }

        // 5 seconds drain time + 10 idle + 5 padding
        untilAsserted(wait = Duration(20, TimeUnit.SECONDS)) {
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
            assertHasOrHadActiveConnection(envoyContainer1, "http1")
        }

        // 10 idle + 5 padding
        untilAsserted(wait = Duration(15, TimeUnit.SECONDS)) {
            assertHasNoActiveConnections(envoyContainer1)
        }
    }

    private fun callProxy() {
        val response = callService("proxy1")
        assertThat(response).isOk()
    }

    private fun assertHasOrHadActiveConnection(container: EnvoyContainer, protocol: String) {
        val stats = container.admin().statsValue(
                listOf("cluster.proxy1.upstream_cx_active", "cluster.proxy1.upstream_cx_${protocol}_total")
        )
        val activeConnections = (stats?.getOrElse(0) { "-1" } ?: "-1").toInt()
        val totalConnections = (stats?.getOrElse(1) { "-1" } ?: "-1").toInt()

        // on CI sometimes the connection is already terminated so we just want to make sure the connection was active
        if (activeConnections == 0) {
            assertThat(totalConnections).isEqualTo(1)
        } else {
            assertThat(activeConnections).isEqualTo(1)
        }
    }

    private fun assertHasNoActiveConnections(container: EnvoyContainer) {
        val activeConnections = container.admin().statValue("cluster.proxy1.upstream_cx_active")?.toInt()
        assertThat(activeConnections).isEqualTo(0)
    }
}
