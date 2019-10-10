package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions
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
    fun `should establish http connection between envoy and a service by default`() {
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
