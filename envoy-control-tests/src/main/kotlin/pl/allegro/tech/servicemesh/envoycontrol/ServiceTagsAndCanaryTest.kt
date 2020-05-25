package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlRunnerTestApp
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration
import pl.allegro.tech.servicemesh.envoycontrol.config.containers.EchoContainer

open class ServiceTagsAndCanaryTest : EnvoyControlTestConfiguration() {

    companion object {
        private val properties = mapOf(
            "envoy-control.envoy.snapshot.routing.service-tags.enabled" to true,
            "envoy-control.envoy.snapshot.routing.service-tags.metadata-key" to "tag",
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

        private val loremRegularContainer = echoContainer
        private val loremCanaryContainer = echoContainer2
        private val ipsumRegularContainer = EchoContainer().also { it.start() }

        @JvmStatic
        @AfterAll
        fun cleanup() {
            ipsumRegularContainer.stop()
        }
    }

    protected fun registerServices() {
        registerService(name = "echo1", container = loremRegularContainer, tags = listOf("lorem"))
        registerService(name = "echo1", container = loremCanaryContainer, tags = listOf("lorem", "canary"))
        registerService(name = "echo1", container = ipsumRegularContainer, tags = listOf("ipsum"))
    }

    @Test
    fun `should route requests to canary instance with tag lorem`() {
        // given
        registerServices()
        waitForReadyServices("echo1")

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
        waitForReadyServices("echo1")

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
        waitForReadyServices("echo1")

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
        waitForReadyServices("echo1")

        // when
        val stats = callEchoServiceRepeatedly(repeat = 10, tag = "not-found", canary = true, assertNoErrors = false)

        // then
        assertThat(stats.totalHits).isEqualTo(10)
        assertThat(stats.failedHits).isEqualTo(10)
        assertThat(stats.loremCanaryHits).isEqualTo(0)
        assertThat(stats.loremRegularHits).isEqualTo(0)
        assertThat(stats.ipsumRegularHits).isEqualTo(0)
    }

    protected open fun callStats() = CallStats(listOf(loremCanaryContainer, loremRegularContainer, ipsumRegularContainer))

    protected open fun callEchoServiceRepeatedly(
        repeat: Int,
        tag: String? = null,
        canary: Boolean,
        assertNoErrors: Boolean = true
    ): CallStats {
        val stats = callStats()
        val tagHeader = tag?.let { mapOf("x-service-tag" to it) } ?: emptyMap()
        val canaryHeader = if (canary) mapOf("x-canary" to "1") else emptyMap()

        callServiceRepeatedly(
            service = "echo1",
            stats = stats,
            minRepeat = repeat,
            maxRepeat = repeat,
            headers = tagHeader + canaryHeader,
            assertNoErrors = assertNoErrors
        )
        return stats
    }

    val CallStats.loremCanaryHits: Int
        get() = this.hits(loremCanaryContainer)
    val CallStats.loremRegularHits: Int
        get() = this.hits(loremRegularContainer)
    val CallStats.ipsumRegularHits: Int
        get() = this.hits(ipsumRegularContainer)
}
