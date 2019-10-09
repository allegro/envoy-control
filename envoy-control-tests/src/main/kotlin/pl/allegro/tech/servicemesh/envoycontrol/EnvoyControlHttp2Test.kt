package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.config.Ads
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyContainer

internal class EnvoyControlHttp2Test : EnvoyControlTestConfiguration() {
    companion object {
        @BeforeAll
        @JvmStatic
        fun setupTest() {
            setup(
                envoyConfig = Ads,
                envoys = 2
            )
        }
    }

    @Test
    fun `should establish http2 connection between envoys`() {
        // given
        registerService(name = "proxy1", port = EnvoyContainer.INGRESS_LISTENER_CONTAINER_PORT, container = envoyContainer2, tags = listOf("envoy"))

        untilAsserted {
            // when
            val response = callService("proxy1")
            // then
            Assertions.assertThat(response).isOk()

            val http1Connections = envoyContainer1.admin().statValue("cluster.proxy1.upstream_cx_http1_total")?.toInt()
            Assertions.assertThat(http1Connections).isEqualTo(0)

            val http2Connections = envoyContainer1.admin().statValue("cluster.proxy1.upstream_cx_http2_total")?.toInt()
            Assertions.assertThat(http2Connections).isGreaterThan(0)
        }
    }

    @Test
    fun `should establish http connection between envoy and a service`() {
        // given
        registerService(name = "echo")

        untilAsserted {
            // when
            val response = callEcho()
            // then
            Assertions.assertThat(response).isOk().isFrom(echoContainer)

            val http1Connections = envoyContainer1.admin().statValue("cluster.echo.upstream_cx_http1_total")?.toInt()
            Assertions.assertThat(http1Connections).isGreaterThan(0)

            val http2Connections = envoyContainer1.admin().statValue("cluster.echo.upstream_cx_http2_total")?.toInt()
            Assertions.assertThat(http2Connections).isEqualTo(0)
        }
    }
}
