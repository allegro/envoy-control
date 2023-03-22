package pl.allegro.tech.servicemesh.envoycontrol.routing

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isOk
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.CallStats
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension

class ServiceTagsAndCanaryTestBase {
    companion object {

        @JvmField
        @RegisterExtension
        val loremRegularService = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val loremCanaryService = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val ipsumRegularService = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(consul, mapOf(
            "envoy-control.envoy.snapshot.routing.service-tags.enabled" to true,
            "envoy-control.envoy.snapshot.routing.service-tags.metadata-key" to "tag",
            "envoy-control.envoy.snapshot.load-balancing.canary.enabled" to true,
            "envoy-control.envoy.snapshot.load-balancing.canary.metadata-key" to "canary",
            "envoy-control.envoy.snapshot.load-balancing.canary.metadata-value" to "1"
        ))

        @JvmField
        @RegisterExtension
        val envoy = EnvoyExtension(envoyControl)
    }

    private fun registerServices() {
        consul.server.operations.registerService(loremRegularService, name = "echo", tags = listOf("lorem"))
        consul.server.operations.registerService(loremCanaryService, name = "echo", tags = listOf("lorem", "canary"))
        consul.server.operations.registerService(ipsumRegularService, name = "echo", tags = listOf("ipsum"))
    }

    @Test
    fun `should route requests to canary instance with tag lorem`() {
        // given
        registerServices()
        waitForReadyServices("echo")

        // when
        val stats = callEchoServiceRepeatedly(repeat = 10, tag = "lorem", canary = true)

        // then
        assertThat(stats.totalHits).isEqualTo(10)
        assertThat(stats.loremCanaryHits).isEqualTo(10)
        assertThat(stats.loremRegularHits).isEqualTo(0)
        assertThat(stats.ipsumRegularHits).isEqualTo(0)
    }

    @Test
    fun `should fallback to regular instance with tag ipsum if canary instance doesn't exist`() {
        // given
        registerServices()
        waitForReadyServices("echo")

        // when
        val stats = callEchoServiceRepeatedly(repeat = 10, tag = "ipsum", canary = true)

        // then
        assertThat(stats.totalHits).isEqualTo(10)
        assertThat(stats.loremCanaryHits).isEqualTo(0)
        assertThat(stats.loremRegularHits).isEqualTo(0)
        assertThat(stats.ipsumRegularHits).isEqualTo(10)
    }

    @Test
    fun `should route requests to canary instance`() {
        // given
        registerServices()
        waitForReadyServices("echo")

        // when
        val stats = callEchoServiceRepeatedly(repeat = 10, canary = true)

        // then
        assertThat(stats.totalHits).isEqualTo(10)
        assertThat(stats.loremCanaryHits).isEqualTo(10)
        assertThat(stats.loremRegularHits).isEqualTo(0)
        assertThat(stats.ipsumRegularHits).isEqualTo(0)
    }

    @Test
    fun `should return 503 if no instance with requested tag is found`() {
        // given
        registerServices()
        waitForReadyServices("echo")

        // when
        val stats = callEchoServiceRepeatedly(repeat = 10, tag = "not-found", canary = true, assertNoErrors = false)

        // then
        assertThat(stats.totalHits).isEqualTo(10)
        assertThat(stats.failedHits).isEqualTo(10)
        assertThat(stats.loremCanaryHits).isEqualTo(0)
        assertThat(stats.loremRegularHits).isEqualTo(0)
        assertThat(stats.ipsumRegularHits).isEqualTo(0)
    }

    private fun waitForReadyServices(vararg serviceNames: String) {
        serviceNames.forEach {
            untilAsserted {
                envoy.egressOperations.callService(it).also {
                    assertThat(it).isOk()
                }
            }
        }
    }

    private fun callStats() = CallStats(listOf(loremCanaryService, loremRegularService, ipsumRegularService))

    private fun callEchoServiceRepeatedly(
        repeat: Int,
        tag: String? = null,
        canary: Boolean,
        assertNoErrors: Boolean = true
    ): CallStats {
        val stats = callStats()
        val tagHeader = tag?.let { mapOf("x-service-tag" to it) } ?: emptyMap()
        val canaryHeader = if (canary) mapOf("x-canary" to "1") else emptyMap()

        envoy.egressOperations.callServiceRepeatedly(
            service = "echo",
            stats = stats,
            minRepeat = repeat,
            maxRepeat = repeat,
            headers = tagHeader + canaryHeader,
            assertNoErrors = assertNoErrors
        )
        return stats
    }

    private val CallStats.loremCanaryHits: Int
        get() = this.hits(loremCanaryService)
    private val CallStats.loremRegularHits: Int
        get() = this.hits(loremRegularService)
    private val CallStats.ipsumRegularHits: Int
        get() = this.hits(ipsumRegularService)
}
