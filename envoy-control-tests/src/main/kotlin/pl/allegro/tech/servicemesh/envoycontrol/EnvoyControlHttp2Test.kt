package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.config.Ads
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration
import pl.allegro.tech.servicemesh.envoycontrol.config.Xds
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyContainer

internal class XdsEnvoyControlHttp2Test : EnvoyControlHttp2Test() {
    companion object {
        @JvmStatic
        @BeforeAll
        fun xdsSetup() {
            setup(envoyConfig = Xds, envoys = 2)
        }
    }
}

internal class AdsEnvoyControlHttp2Test : EnvoyControlHttp2Test() {
    companion object {
        @JvmStatic
        @BeforeAll
        fun adsSetup() {
            setup(envoyConfig = Ads, envoys = 2)
        }
    }
}

abstract class EnvoyControlHttp2Test : EnvoyControlTestConfiguration() {
    @Test
    fun `should establish http2 connection between envoys`() {
        // given
        registerService(name = "proxy1", port = EnvoyContainer.INGRESS_LISTENER_CONTAINER_PORT, container = envoyContainer2, tags = listOf("envoy"))

        untilAsserted {
            // when
            callProxy()

            // then
            assertDidNotUseHttp1(envoyContainer1)
            assertUsedHttp2(envoyContainer1)
        }
    }

    @Test
    fun `should establish http1 connection between envoy and a service by default`() {
        // given
        registerService(name = "echo")

        untilAsserted {
            // when
            val response = callEcho()
            assertThat(response).isOk().isFrom(echoContainer)

            // then
            assertUsedHttp1(envoyContainer1)
            assertDidNotUseHttp2(envoyContainer1)
        }
    }

    private fun callProxy() {
        val response = callService("proxy1")
        assertThat(response).isOk()
    }

    private fun assertUsedHttp2(container: EnvoyContainer) {
        val http2Connections = container.admin().statValue("cluster.proxy1.upstream_cx_http2_total")?.toInt()
        assertThat(http2Connections).isGreaterThan(0)
    }

    private fun assertDidNotUseHttp2(container: EnvoyContainer) {
        val http2Connections = container.admin().statValue("cluster.echo.upstream_cx_http2_total")?.toInt()
        assertThat(http2Connections).isEqualTo(0)
    }

    private fun assertDidNotUseHttp1(container: EnvoyContainer) {
        val http1Connections = container.admin().statValue("cluster.proxy1.upstream_cx_http1_total")?.toInt()
        assertThat(http1Connections).isEqualTo(0)
    }

    private fun assertUsedHttp1(container: EnvoyContainer) {
        val http1Connections = container.admin().statValue("cluster.echo.upstream_cx_http1_total")?.toInt()
        assertThat(http1Connections).isGreaterThan(0)
    }
}
