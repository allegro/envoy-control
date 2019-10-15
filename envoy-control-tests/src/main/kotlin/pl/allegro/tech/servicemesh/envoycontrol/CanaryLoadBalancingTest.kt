package pl.allegro.tech.servicemesh.envoycontrol

import okhttp3.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlRunnerTestApp
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration
import pl.allegro.tech.servicemesh.envoycontrol.config.containers.EchoContainer

open class CanaryLoadBalancingTest : EnvoyControlTestConfiguration() {

    companion object {
        private val properties = mapOf(
            "envoy-control.source.consul.tags.weight" to "weight",
            "envoy-control.source.consul.tags.canary" to "canary",
            "envoy-control.envoy.snapshot.load-balancing.weights.enabled" to true,
            "envoy-control.envoy.snapshot.load-balancing.canary.enabled" to true,
            "envoy-control.envoy.snapshot.load-balancing.canary.metadata-key" to "canary",
            "envoy-control.envoy.snapshot.load-balancing.canary.metadata-value" to "1"
        )

        @JvmStatic
        @BeforeAll
        fun setupTest() {
            setup(appFactoryForEc1 = { consulPort ->
                EnvoyControlRunnerTestApp(properties = properties, consulPort = consulPort)
            })
        }
    }

    private val canaryContainer = echoContainer
    private val regularContainer = echoContainer2

    @Test
    fun `should balance load according to weights`() {
        // given
        registerService(name = "echo", container = canaryContainer, tags = listOf("canary", "weight:1"))
        registerService(name = "echo", container = regularContainer, tags = listOf("weight:20"))

        untilAsserted {
            callService("echo").also {
                assertThat(it).isOk()
            }
        }

        // when
        val callStats = callServiceRepeatedly(
            service = "echo",
            minRepeat = 30,
            maxRepeat = 200,
            repeatUntil = { response -> response.isFrom(canaryContainer) }
        )

        // then
        assertThat(callStats.canaryHits + callStats.regularHits).isEqualTo(callStats.totalHits)
        assertThat(callStats.totalHits).isGreaterThan(29)
        assertThat(callStats.canaryHits).isGreaterThan(0)
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
        assertThat(callStats.regularHits).isGreaterThan(callStats.canaryHits * 3)
    }

    @Test
    fun `should route request to canary instance only`() {
        // given
        registerService(name = "echo", container = canaryContainer, tags = listOf("canary", "weight:1"))
        registerService(name = "echo", container = regularContainer, tags = listOf("weight:20"))

        untilAsserted {
            callService("echo").also {
                assertThat(it).isOk()
            }
        }

        // when
        val callStats = callServiceRepeatedly(
            service = "echo",
            minRepeat = 50,
            maxRepeat = 50,
            headers = mapOf("X-Canary" to "1")
        )

        // then
        assertThat(callStats.totalHits).isEqualTo(50)
        assertThat(callStats.canaryHits).isEqualTo(50)
        assertThat(callStats.regularHits).isEqualTo(0)
    }

    @Test
    open fun `should route to both canary and regular instances when canary weight is 0`() {
        registerService(name = "echo", container = canaryContainer, tags = listOf("canary", "weight:0"))
        registerService(name = "echo", container = regularContainer, tags = listOf("weight:20"))

        untilAsserted {
            callService("echo").also {
                assertThat(it).isOk()
            }
        }

        // when
        val callStats = callServiceRepeatedly(
            service = "echo",
            minRepeat = 30,
            maxRepeat = 200,
            repeatUntil = { response -> response.isFrom(canaryContainer) }
        )

        // then
        assertThat(callStats.canaryHits + callStats.regularHits).isEqualTo(callStats.totalHits)
        assertThat(callStats.totalHits).isGreaterThan(29)
        assertThat(callStats.canaryHits).isGreaterThan(0)
    }

    data class CallStatistics(val canaryHits: Int = 0, val regularHits: Int = 0, val totalHits: Int = 0) {
        operator fun plus(other: CallStatistics): CallStatistics = CallStatistics(
            canaryHits = this.canaryHits + other.canaryHits,
            regularHits = this.regularHits + other.regularHits,
            totalHits = this.totalHits + other.totalHits
        )
    }

    data class ResponseWithBody(val response: Response, val body: String) {
        fun isFrom(echoContainer: EchoContainer) = body.contains(echoContainer.response)
    }

    private fun mapResponseToCallStats(response: ResponseWithBody): CallStatistics = CallStatistics(
        canaryHits = if (response.isFrom(canaryContainer)) 1 else 0,
        regularHits = if (response.isFrom(regularContainer)) 1 else 0,
        totalHits = 1
    )

    protected fun callServiceRepeatedly(
        service: String,
        minRepeat: Int = 1,
        maxRepeat: Int = 100,
        repeatUntil: (ResponseWithBody) -> Boolean = { false },
        headers: Map<String, String> = mapOf()
    ): CallStatistics =
        (1..maxRepeat).asSequence()
            .map { i ->
                callService(service = service, headers = headers).also {
                    assertThat(it).isOk().describedAs("Error response at attempt $i: \n$it")
                }
            }
            .map { ResponseWithBody(it, it.body()?.string() ?: "") }
            .withIndex()
            .takeWhile { (i, response) -> i < minRepeat || !repeatUntil(response) }
            .map { it.value }
            .fold(CallStatistics()) { acc, response -> acc + mapResponseToCallStats(response) }
}
