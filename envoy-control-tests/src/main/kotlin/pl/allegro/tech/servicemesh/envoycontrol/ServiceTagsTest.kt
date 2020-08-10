package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlRunnerTestApp
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoContainer
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.CallStats

open class ServiceTagsTest : EnvoyControlTestConfiguration() {

    companion object {
        private val properties = mapOf(
            "envoy-control.envoy.snapshot.routing.service-tags.enabled" to true,
            "envoy-control.envoy.snapshot.routing.service-tags.metadata-key" to "tag",
            "envoy-control.envoy.snapshot.routing.service-tags.allowed-tags-combinations[0].service-name" to "service-1",
            "envoy-control.envoy.snapshot.routing.service-tags.allowed-tags-combinations[0].tags" to "version:.*,hardware:.*,role:.*",
            "envoy-control.envoy.snapshot.routing.service-tags.allowed-tags-combinations[1].service-name" to "service-2",
            "envoy-control.envoy.snapshot.routing.service-tags.allowed-tags-combinations[1].tags" to "version:.*,role:.*",
            "envoy-control.envoy.snapshot.routing.service-tags.allowed-tags-combinations[2].service-name" to "service-2",
            "envoy-control.envoy.snapshot.routing.service-tags.allowed-tags-combinations[2].tags" to "version:.*,hardware:.*",
            "envoy-control.envoy.snapshot.routing.service-tags.routing-excluded-tags" to "blacklist.*",
            "envoy-control.envoy.snapshot.load-balancing.canary.enabled" to false
        )

        @JvmStatic
        @BeforeAll
        fun setupTest() {
            setup(appFactoryForEc1 = { consulPort ->
                EnvoyControlRunnerTestApp(properties = properties, consulPort = consulPort)
            })
        }

        val regularContainer = echoContainer
        val loremContainer = echoContainer2
        val loremIpsumContainer = EchoContainer()
        val genericContainer = EchoContainer()

        private val containersToStart = listOf(loremIpsumContainer, genericContainer)

        @JvmStatic
        @BeforeAll
        fun startContainers() {
            containersToStart.parallelStream().forEach { it.start() }
        }

        @JvmStatic
        @AfterAll
        fun cleanup() {
            containersToStart.parallelStream().forEach { it.stop() }
        }
    }

    protected fun registerServices() {
        registerService(name = "echo", container = regularContainer, tags = listOf())
        registerService(name = "echo", container = loremContainer, tags = listOf("lorem", "blacklisted"))
        registerService(name = "echo", container = loremIpsumContainer, tags = listOf("lorem", "ipsum"))
    }

    @Test
    open fun `should route requests to instance with tag ipsum`() {
        // given
        registerServices()
        waitForReadyServices("echo")

        // when
        val stats = callEchoServiceRepeatedly(repeat = 10, tag = "ipsum")

        // then
        assertThat(stats.totalHits).isEqualTo(10)
        assertThat(stats.hits(regularContainer)).isEqualTo(0)
        assertThat(stats.hits(loremContainer)).isEqualTo(0)
        assertThat(stats.hits(loremIpsumContainer)).isEqualTo(10)
    }

    @Test
    open fun `should route requests to instances with tag lorem`() {
        // given
        registerServices()
        waitForReadyServices("echo")

        // when
        val stats = callEchoServiceRepeatedly(repeat = 20, tag = "lorem")

        // then
        assertThat(stats.totalHits).isEqualTo(20)
        assertThat(stats.hits(regularContainer)).isEqualTo(0)
        assertThat(stats.hits(loremContainer)).isGreaterThan(2)
        assertThat(stats.hits(loremIpsumContainer)).isGreaterThan(2)
        assertThat(stats.hits(loremContainer) + stats.hits(loremIpsumContainer)).isEqualTo(20)
    }

    @Test
    open fun `should route requests to all instances`() {
        // given
        registerServices()
        waitForReadyServices("echo")

        // when
        val stats = callEchoServiceRepeatedly(repeat = 20)

        // then
        assertThat(stats.totalHits).isEqualTo(20)
        assertThat(stats.hits(regularContainer)).isGreaterThan(1)
        assertThat(stats.hits(loremContainer)).isGreaterThan(1)
        assertThat(stats.hits(loremIpsumContainer)).isGreaterThan(1)
        assertThat(stats.hits(regularContainer) + stats.hits(loremContainer) + stats.hits(loremIpsumContainer)).isEqualTo(20)
    }

    @Test
    open fun `should return 503 if instance with requested tag is not found`() {
        // given
        registerServices()
        waitForReadyServices("echo")

        // when
        val stats = callEchoServiceRepeatedly(repeat = 10, tag = "dolom", assertNoErrors = false)

        // then
        assertThat(stats.totalHits).isEqualTo(10)
        assertThat(stats.failedHits).isEqualTo(10)
        assertThat(stats.hits(regularContainer)).isEqualTo(0)
        assertThat(stats.hits(loremContainer)).isEqualTo(0)
        assertThat(stats.hits(loremIpsumContainer)).isEqualTo(0)
    }

    @Test
    open fun `should return 503 if requested tag is blacklisted`() {
        // given
        registerServices()
        waitForReadyServices("echo")

        // when
        val stats = callEchoServiceRepeatedly(repeat = 10, tag = "blacklisted", assertNoErrors = false)

        // then
        assertThat(stats.totalHits).isEqualTo(10)
        assertThat(stats.failedHits).isEqualTo(10)
        assertThat(stats.hits(regularContainer)).isEqualTo(0)
        assertThat(stats.hits(loremContainer)).isEqualTo(0)
        assertThat(stats.hits(loremIpsumContainer)).isEqualTo(0)
    }

    @Test
    open fun `should route request with three tags if combination is valid`() {
        // given
        val matchingContainer = loremContainer
        val notMatchingContainer = loremIpsumContainer

        registerService(
            name = "service-1", container = matchingContainer,
            tags = listOf("version:v1.5", "hardware:c32", "role:master"))
        registerService(
            name = "service-1", container = notMatchingContainer,
            tags = listOf("version:v1.5", "hardware:c64", "role:master"))

        waitForReadyServices("service-1")

        // when
        val stats = callServiceRepeatedly(
            service = "service-1", repeat = 10, tag = "hardware:c32,role:master,version:v1.5")

        // then
        assertThat(stats.totalHits).isEqualTo(10)
        assertThat(stats.hits(matchingContainer)).isEqualTo(10)
        assertThat(stats.hits(notMatchingContainer)).isEqualTo(0)
    }

    @Test
    open fun `should not route request with multiple tags if service is not whitelisted`() {
        // given
        val matchingContainer = loremContainer

        registerService(
            name = "service-3", container = matchingContainer,
            tags = listOf("version:v1.5", "hardware:c32", "role:master"))

        waitForReadyServices("service-3")

        // when
        val threeTagsStats = callServiceRepeatedly(
            service = "service-3", repeat = 10, tag = "hardware:c32,role:master,version:v1.5", assertNoErrors = false)
        val twoTagsStats = callServiceRepeatedly(
            service = "service-3", repeat = 10, tag = "hardware:c32,role:master", assertNoErrors = false)
        val oneTagStats = callServiceRepeatedly(
            service = "service-3", repeat = 10, tag = "role:master")

        // then
        assertThat(threeTagsStats.totalHits).isEqualTo(10)
        assertThat(threeTagsStats.failedHits).isEqualTo(10)
        assertThat(threeTagsStats.hits(matchingContainer)).isEqualTo(0)

        assertThat(twoTagsStats.totalHits).isEqualTo(10)
        assertThat(twoTagsStats.failedHits).isEqualTo(10)
        assertThat(twoTagsStats.hits(matchingContainer)).isEqualTo(0)

        assertThat(oneTagStats.totalHits).isEqualTo(10)
        assertThat(oneTagStats.failedHits).isEqualTo(0)
        assertThat(oneTagStats.hits(matchingContainer)).isEqualTo(10)
    }

    @Test
    open fun `should not route request with three tags if combination is not allowed`() {
        // given
        val service1MatchingContainer = loremContainer
        val service2MatchingContainer = loremIpsumContainer

        registerService(
            name = "service-1", container = service1MatchingContainer,
            tags = listOf("version:v1.5", "hardware:c32", "ram:512"))
        registerService(
            name = "service-2", container = service2MatchingContainer,
            tags = listOf("version:v1.5", "hardware:c32", "role:master"))

        waitForReadyServices("service-1", "service-2")

        // when
        val service1Stats = callServiceRepeatedly(
            service = "service-1", repeat = 10, tag = "hardware:c32,ram:512,version:v1.5", assertNoErrors = false)
        val service2Stats = callServiceRepeatedly(
            service = "service-2", repeat = 10, tag = "hardware:c32,role:master,version:v1.5", assertNoErrors = false)

        // then
        assertThat(service1Stats.totalHits).isEqualTo(10)
        assertThat(service1Stats.failedHits).isEqualTo(10)
        assertThat(service1Stats.hits(service1MatchingContainer)).isEqualTo(0)

        assertThat(service2Stats.totalHits).isEqualTo(10)
        assertThat(service2Stats.failedHits).isEqualTo(10)
        assertThat(service2Stats.hits(service1MatchingContainer)).isEqualTo(0)
    }

    @Test
    open fun `should route request with two tags if combination is valid`() {
        // given
        val service1MatchingContainer = loremContainer
        val service1NotMatchingContainer = regularContainer
        val service2MasterContainer = loremIpsumContainer
        val service2SecondaryContainer = genericContainer

        registerService(
            name = "service-1", container = service1MatchingContainer,
            tags = listOf("version:v2.0", "hardware:c32", "role:master"))
        registerService(
            name = "service-1", container = service1NotMatchingContainer,
            tags = listOf("version:v1.5", "hardware:c32", "role:master"))
        registerService(
            name = "service-2", container = service2MasterContainer,
            tags = listOf("version:v1.5", "hardware:c32", "role:master"))
        registerService(
            name = "service-2", container = service2SecondaryContainer,
            tags = listOf("version:v2.0", "hardware:c32", "role:secondary"))

        waitForReadyServices("service-1", "service-2")

        // when
        val service1Stats = callServiceRepeatedly(
            service = "service-1", repeat = 10, tag = "hardware:c32,version:v2.0")
        val service2MasterStats = callServiceRepeatedly(
            service = "service-2", repeat = 10, tag = "hardware:c32,version:v1.5")
        val service2SecondaryStats = callServiceRepeatedly(
            service = "service-2", repeat = 10, tag = "role:secondary,version:v2.0")

        // then
        assertThat(service1Stats.totalHits).isEqualTo(10)
        assertThat(service1Stats.hits(service1MatchingContainer)).isEqualTo(10)

        assertThat(service2MasterStats.totalHits).isEqualTo(10)
        assertThat(service2MasterStats.hits(service2MasterContainer)).isEqualTo(10)

        assertThat(service2SecondaryStats.totalHits).isEqualTo(10)
        assertThat(service2SecondaryStats.hits(service2SecondaryContainer)).isEqualTo(10)
    }

    @Test
    open fun `should not route request with two tags if combination is not allowed`() {
        // given
        val matchingContainer = loremContainer

        registerService(
            name = "service-2", container = matchingContainer,
            tags = listOf("version:v1.5", "hardware:c32", "role:master"))

        waitForReadyServices("service-2")

        // when
        val stats = callServiceRepeatedly(
            service = "service-2", repeat = 10, tag = "hardware:c32,role:master", assertNoErrors = false)

        // then
        assertThat(stats.totalHits).isEqualTo(10)
        assertThat(stats.failedHits).isEqualTo(10)
        assertThat(stats.hits(matchingContainer)).isEqualTo(0)
    }

    protected fun callEchoServiceRepeatedly(repeat: Int, tag: String? = null, assertNoErrors: Boolean = true): CallStats {
        return callServiceRepeatedly(
            service = "echo",
            repeat = repeat,
            tag = tag,
            assertNoErrors = assertNoErrors
        )
    }

    protected open fun callStats() = CallStats(listOf(regularContainer, loremContainer) + containersToStart)

    protected open fun callServiceRepeatedly(service: String, repeat: Int, tag: String? = null, assertNoErrors: Boolean = true): CallStats {
        val stats = callStats()
        callServiceRepeatedly(
            service = service,
            stats = stats,
            minRepeat = repeat,
            maxRepeat = repeat,
            headers = tag?.let { mapOf("x-service-tag" to it) } ?: emptyMap(),
            assertNoErrors = assertNoErrors
        )
        return stats
    }
}
