package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
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
    fun `should merge all service tags of endpoints with the same ip and port`() {
        // given
        consul.server.operations.registerService(name = "echo", extension = service, tags = listOf("ipsum"))
        consul.server.operations.registerService(name = "echo", extension = service, tags = listOf("lorem", "dolom"))

        // TODO: flaky test. I'm not sure why, but it fails time to time.
        //       Theoretically after one call service should be ready,
        //       but for some reason sometimes is not and returns 503.
        repeat(3) {
            envoy.waitForReadyServices("echo")
        }

        // when
        val ipsumStats = callEchoServiceRepeatedly(repeat = 1, tag = "ipsum")
        val loremStats = callEchoServiceRepeatedly(repeat = 1, tag = "lorem")
        val dolomStats = callEchoServiceRepeatedly(repeat = 1, tag = "dolom")

        // then
        assertThat(ipsumStats.hits(service)).isEqualTo(1)
        assertThat(loremStats.hits(service)).isEqualTo(1)
        assertThat(dolomStats.hits(service)).isEqualTo(1)
    }

    protected open fun callEchoServiceRepeatedly(
        repeat: Int,
        tag: String,
    ): CallStats {
        val stats = CallStats(listOf(service))
        envoy.egressOperations.callServiceRepeatedly(
            service = "echo",
            stats = stats,
            minRepeat = repeat,
            maxRepeat = repeat,
            headers = mapOf("x-service-tag" to tag),
            assertNoErrors = true
        )
        return stats
    }
}
