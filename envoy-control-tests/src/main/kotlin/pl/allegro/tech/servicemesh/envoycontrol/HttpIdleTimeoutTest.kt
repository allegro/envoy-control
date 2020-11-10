package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isOk
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyContainer
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension
import java.time.Duration

class HttpIdleTimeoutTest {

    companion object {

        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(
            consul, mapOf(
                "envoy-control.envoy.snapshot.egress.commonHttp.idleTimeout" to Duration.ofSeconds(10)
            )
        )

        @JvmField
        @RegisterExtension
        val service = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val envoy = EnvoyExtension(envoyControl, service)

        @JvmField
        @RegisterExtension
        val secondEnvoy = EnvoyExtension(envoyControl, service)
    }

    @Test
    fun `should close idle connections after 1s for HTTP2`() {
        // given
        val name = "proxy1"
        consul.server.operations.registerService(
            name = name,
            address = secondEnvoy.container.ipAddress(),
            port = EnvoyContainer.INGRESS_LISTENER_CONTAINER_PORT,
            tags = listOf("envoy")
        )

        // when
        untilAsserted {
            assertThat(envoy.egressOperations.callService(name)).isOk()
        }

        // then
        untilAsserted {
            assertThat(activeConnections(envoy, name)).isEqualTo(1)
        }

        // 5 seconds drain time + 10 idle + 5 padding
        untilAsserted(wait = Duration.ofSeconds(20)) {
            assertThat(activeConnections(envoy, name)).isEqualTo(0)
        }
    }

    @Test
    fun `should close idle connections after 1s for HTTP1`() {
        // given
        val name = "proxy2"
        consul.server.operations.registerService(
            name = name,
            address = secondEnvoy.container.ipAddress(),
            port = EnvoyContainer.INGRESS_LISTENER_CONTAINER_PORT
        )

        // when
        untilAsserted {
            assertThat(envoy.egressOperations.callService(name)).isOk()
        }

        // then
        untilAsserted {
            assertThat(activeConnections(envoy, name)).isEqualTo(1)
        }

        // 10 idle + 5 padding
        untilAsserted(wait = Duration.ofSeconds(15)) {
            assertThat(activeConnections(envoy, name)).isEqualTo(0)
        }
    }

    fun activeConnections(envoy: EnvoyExtension, name: String) =
        envoy.container.admin().statValue("cluster.$name.upstream_cx_active")?.toInt()
}
