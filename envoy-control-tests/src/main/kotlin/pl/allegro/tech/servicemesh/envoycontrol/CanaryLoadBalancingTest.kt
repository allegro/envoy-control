package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isOk
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.CallStats
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.ResponseWithBody
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension

open class CanaryLoadBalancingTest {

    companion object {
        private val properties = mapOf(
            "envoy-control.source.consul.tags.weight" to "weight",
            "envoy-control.source.consul.tags.canary" to "canary",
            "envoy-control.envoy.snapshot.load-balancing.weights.enabled" to true,
            "envoy-control.envoy.snapshot.load-balancing.canary.enabled" to true,
            "envoy-control.envoy.snapshot.load-balancing.canary.metadata-key" to "canary",
            "envoy-control.envoy.snapshot.load-balancing.canary.metadata-value" to "1"
        )

        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(consul, properties)

        @JvmField
        @RegisterExtension
        val canaryContainer = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val regularContainer = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val envoy = EnvoyExtension(envoyControl)
    }

    @Test
    fun `should balance load according to weights`() {
        // given
        consul.server.operations.registerService(name = "echo", extension = canaryContainer, tags = listOf("canary", "weight:1"))
        consul.server.operations.registerService(name = "echo", extension = regularContainer, tags = listOf("weight:20"))

        untilAsserted {
            envoy.egressOperations.callService("echo").also {
                assertThat(it).isOk()
            }
        }

        // when
        val stats = callEchoServiceRepeatedly(
            minRepeat = 30,
            maxRepeat = 200,
            repeatUntil = { response -> response.isFrom(canaryContainer.container()) }
        )

        // then
        assertThat(stats.canaryHits + stats.regularHits).isEqualTo(stats.totalHits)
        assertThat(stats.totalHits).isGreaterThan(29)
        assertThat(stats.canaryHits).isGreaterThan(0)
        /**
         * The condition below tests if the regular instance received at least 3x more requests than the canary
         * instance.
         * According to weights, the regular instance should receive approximately 20x more requests than the canary
         * instance. But load balancing is a random process, so we cannot assume that 20x factor will be achieved every
         * time.
         *
         * The test is not 100% deterministic. There is very little chance, that the test will
         * fail even if everything is ok (false negative) or will pass even if something is wrong (false positive).
         * The factor of 3 is chosen to minimize the chance of both false result types:
         *
         * False positive -> if weighted load balancing doesn't work correctly, instances should receive approximately
         * the same number of requests. There will be at least 30 request. The chance that regular instance will
         * receive more than 3/4 of them is very small.
         *
         * False negative -> if weighted load balancing works correctly, with at least 30 requests, the chance that
         * the regular instance will receive less than 3/4 of them is very small.
         */
        assertThat(stats.regularHits).isGreaterThan(stats.canaryHits * 3)
    }

    @Test
    fun `should route request to canary instance only`() {
        // given
        consul.server.operations.registerService(name = "echo", extension = canaryContainer, tags = listOf("canary", "weight:1"))
        consul.server.operations.registerService(name = "echo", extension = regularContainer, tags = listOf("weight:20"))

        untilAsserted {
            envoy.egressOperations.callService("echo").also {
                assertThat(it).isOk()
            }
        }

        // when
        val stats = callEchoServiceRepeatedly(
            minRepeat = 50,
            maxRepeat = 50,
            headers = mapOf("X-Canary" to "1")
        )

        // then
        assertThat(stats.totalHits).isEqualTo(50)
        assertThat(stats.canaryHits).isEqualTo(50)
        assertThat(stats.regularHits).isEqualTo(0)
    }

    @Test
    open fun `should route to both canary and regular instances when canary weight is 0`() {
        consul.server.operations.registerService(name = "echo", extension = canaryContainer, tags = listOf("canary", "weight:0"))
        consul.server.operations.registerService(name = "echo", extension = regularContainer, tags = listOf("weight:20"))

        untilAsserted {
            envoy.egressOperations.callService("echo").also {
                assertThat(it).isOk()
            }
        }

        // when
        val stats = callEchoServiceRepeatedly(
            minRepeat = 30,
            maxRepeat = 200,
            repeatUntil = { response -> response.isFrom(canaryContainer.container()) }
        )

        // then
        assertThat(stats.canaryHits + stats.regularHits).isEqualTo(stats.totalHits)
        assertThat(stats.totalHits).isGreaterThan(29)
        assertThat(stats.canaryHits).isGreaterThan(0)
    }

    protected open fun callStats() = CallStats(listOf(canaryContainer.container(), regularContainer.container()))

    fun callEchoServiceRepeatedly(
        minRepeat: Int,
        maxRepeat: Int,
        repeatUntil: (ResponseWithBody) -> Boolean = { false },
        headers: Map<String, String> = mapOf()
    ): CallStats {
        val stats = callStats()
        envoy.egressOperations.callServiceRepeatedly(
            service = "echo",
            stats = stats,
            minRepeat = minRepeat,
            maxRepeat = maxRepeat,
            repeatUntil = repeatUntil,
            headers = headers
        )
        return stats
    }

    val CallStats.regularHits: Int
        get() = this.hits(regularContainer.container())
    val CallStats.canaryHits: Int
        get() = this.hits(canaryContainer.container())
}
