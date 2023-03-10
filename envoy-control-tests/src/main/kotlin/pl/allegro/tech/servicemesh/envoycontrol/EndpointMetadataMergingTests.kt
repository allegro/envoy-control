package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.CallStats
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension

open class EndpointMetadataMergingTests {

    companion object {
        private val properties = mapOf(
            "envoy-control.envoy.snapshot.routing.service-tags.enabled" to true
        )

        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(consul, properties)

        @JvmField
        @RegisterExtension
        val service = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val envoy = EnvoyExtension(envoyControl, service)
    }

    @Test
    @Disabled("flaky")
    fun `should merge all service tags of endpoints with the same ip and port`() {
        // given
        consul.server.operations.registerService(name = "echo", extension = service, tags = listOf("ipsum"))
        consul.server.operations.registerService(name = "echo", extension = service, tags = listOf("lorem", "dolom"))

        untilAsserted {
            assertThat(consul.server.operations.getService("echo")).hasSize(2)
        }

        envoy.waitForAvailableEndpoints("echo")

        // when
        val ipsumStats = callEchoServiceRepeatedly(service, repeat = 1, tag = "ipsum")
        val loremStats = callEchoServiceRepeatedly(service, repeat = 1, tag = "lorem")
        val dolomStats = callEchoServiceRepeatedly(service, repeat = 1, tag = "dolom")

        // then
        assertThat(ipsumStats.hits(service)).isEqualTo(1)
        assertThat(loremStats.hits(service)).isEqualTo(1)
        assertThat(dolomStats.hits(service)).isEqualTo(1)
    }

    protected open fun callEchoServiceRepeatedly(
        service: EchoServiceExtension,
        repeat: Int,
        tag: String? = null,
        assertNoErrors: Boolean = true
    ): CallStats {
        val stats = CallStats(listOf(service))
        envoy.egressOperations.callServiceRepeatedly(
            service = "echo",
            stats = stats,
            minRepeat = repeat,
            maxRepeat = repeat,
            headers = tag?.let { mapOf("x-service-tag" to it) } ?: emptyMap(),
            assertNoErrors = assertNoErrors
        )
        return stats
    }
}
