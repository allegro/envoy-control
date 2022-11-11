package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isFrom
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isOk
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.Ads
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyContainer
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension

class AdsEnvoyControlHttp2Test : EnvoyControlHttp2Test {
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
        val envoy = EnvoyExtension(envoyControl, service, config = Ads)

        @JvmField
        @RegisterExtension
        val secondEnvoy = EnvoyExtension(envoyControl, service, config = Ads)
    }

    override fun consul() = consul

    override fun envoyControl() = envoyControl

    override fun service() = service

    override fun envoy() = envoy

    override fun secondEnvoy() = secondEnvoy
}

interface EnvoyControlHttp2Test {

    fun consul(): ConsulExtension

    fun envoyControl(): EnvoyControlExtension

    fun service(): EchoServiceExtension

    fun envoy(): EnvoyExtension

    fun secondEnvoy(): EnvoyExtension

    @Test
    fun `should establish http2 connection between envoys`() {
        // given
        consul().server.operations.registerService(
            name = "proxy1",
            address = secondEnvoy().container.ipAddress(),
            port = EnvoyContainer.INGRESS_LISTENER_CONTAINER_PORT,
            tags = listOf("envoy")
        )

        untilAsserted {
            // when
            val response = envoy().egressOperations.callService("proxy1")
            assertThat(response).isOk()

            // then
            assertDidNotUseHttp1(envoy())
            assertUsedHttp2(envoy())
        }
    }

    @Test
    fun `should establish http1 connection between envoy and a service by default`() {
        // given
        consul().server.operations.registerService(service(), name = "echo")

        untilAsserted {
            // when
            val response = envoy().egressOperations.callService("echo")
            assertThat(response).isOk().isFrom(service())

            // then
            assertUsedHttp1(envoy())
            assertDidNotUseHttp2(envoy())
        }
    }

    fun assertUsedHttp2(envoy: EnvoyExtension) {
        val http2Connections = envoy.container.admin().statValue("cluster.proxy1.upstream_cx_http2_total")?.toInt()
        assertThat(http2Connections).isGreaterThan(0)
    }

    fun assertDidNotUseHttp2(envoy: EnvoyExtension) {
        val http2Connections = envoy.container.admin().statValue("cluster.echo.upstream_cx_http2_total")?.toInt()
        assertThat(http2Connections).isEqualTo(0)
    }

    fun assertDidNotUseHttp1(envoy: EnvoyExtension) {
        val http1Connections = envoy.container.admin().statValue("cluster.proxy1.upstream_cx_http1_total")?.toInt()
        assertThat(http1Connections).isEqualTo(0)
    }

    fun assertUsedHttp1(envoy: EnvoyExtension) {
        val http1Connections = envoy.container.admin().statValue("cluster.echo.upstream_cx_http1_total")?.toInt()
        assertThat(http1Connections).isGreaterThan(0)
    }
}
