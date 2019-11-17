package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlRunnerTestApp
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration
import pl.allegro.tech.servicemesh.envoycontrol.config.containers.EchoContainer

open class ServiceTagsTest : EnvoyControlTestConfiguration() {

    companion object {
        private val properties = mapOf(
            "envoy-control.envoy.snapshot.routing.service-tags.enabled" to true,
            "envoy-control.envoy.snapshot.routing.service-tags.metadata-key" to "tag",
            "envoy-control.envoy.snapshot.routing.service-tags.two-tags-routing-allowed-services" to "service-1",
            "envoy-control.envoy.snapshot.routing.service-tags.three-tags-routing-allowed-services" to "service-2",
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
        val service1LoremContainer = EchoContainer()
        val service1IpsumContainer = EchoContainer()
        val service1LoremIpsumContainer = EchoContainer()
        val service2DolomContainer = EchoContainer()
        val service2LoremIpsumContainer = EchoContainer()
        val service2LoremIpsumDolomContainer = EchoContainer()

        private val containersToStart = listOf(
            loremIpsumContainer, service1LoremContainer, service1IpsumContainer, service1LoremIpsumContainer,
            service2DolomContainer, service2LoremIpsumContainer, service2LoremIpsumDolomContainer)

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
        registerService(name = "service-1", container = service1LoremContainer, tags = listOf("lorem"))
        registerService(name = "service-1", container = service1IpsumContainer, tags = listOf("ipsum"))
        registerService(name = "service-1", container = service1LoremIpsumContainer, tags = listOf("lorem", "ipsum"))
        registerService(name = "service-2", container = service2LoremIpsumContainer, tags = listOf("lorem", "ipsum"))
        registerService(name = "service-2", container = service2LoremIpsumDolomContainer,
            tags = listOf("lorem", "ipsum", "dolom"))
    }

    @Test
    fun `should route requests to instance with tag ipsum`() {
        // given
        registerServices()
        untilAsserted {
            callService("echo").also {
                assertThat(it).isOk()
            }
        }

        // when
        val stats = callEchoServiceRepeatedly(repeat = 10, tag = "ipsum")

        // then
        assertThat(stats.totalHits).isEqualTo(10)
        assertThat(stats.hits(regularContainer)).isEqualTo(0)
        assertThat(stats.hits(loremContainer)).isEqualTo(0)
        assertThat(stats.hits(loremIpsumContainer)).isEqualTo(10)
    }

    @Test
    fun `should route requests to instances with tag lorem`() {
        // given
        registerServices()
        untilAsserted {
            callService("echo").also {
                assertThat(it).isOk()
            }
        }

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
    fun `should route requests to all instances`() {
        // given
        registerServices()
        untilAsserted {
            callService("echo").also {
                assertThat(it).isOk()
            }
        }

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
    fun `should return 503 if instance with requested tag is not found`() {
        // given
        registerServices()
        untilAsserted {
            callService("echo").also {
                assertThat(it).isOk()
            }
        }

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
        untilAsserted {
            callService("echo").also {
                assertThat(it).isOk()
            }
        }

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
    fun `should route request with two tags if service is on the whitelist`() {
        // given
        registerServices()
        untilAsserted {
            callService("service-1").also {
                assertThat(it).isOk()
            }
        }

        // when
        val stats = callServiceRepeatedly(service = "service-1", repeat = 10, tag = "ipsum,lorem")

        // then
        assertThat(stats.totalHits).isEqualTo(10)
        assertThat(stats.hits(service1LoremIpsumContainer)).isEqualTo(10)
        assertThat(stats.hits(service1LoremContainer)).isEqualTo(0)
        assertThat(stats.hits(service1IpsumContainer)).isEqualTo(0)
    }

    @Test
    fun `should return 503 for request with two tags is service is not on the whitelist`() {
        // given
        registerServices()
        untilAsserted {
            callService("echo").also {
                assertThat(it).isOk()
            }
        }

        // when
        val stats = callEchoServiceRepeatedly(repeat = 10, tag = "ipsum,lorem", assertNoErrors = false)

        // then
        assertThat(stats.totalHits).isEqualTo(10)
        assertThat(stats.failedHits).isEqualTo(10)
        assertThat(stats.hits(regularContainer)).isEqualTo(0)
        assertThat(stats.hits(loremContainer)).isEqualTo(0)
        assertThat(stats.hits(loremIpsumContainer)).isEqualTo(0)
    }

    @Test
    fun `should route request with three tags if service is on the whitelist`() {
        // given
        registerServices()
        untilAsserted {
            callService("service-2").also {
                assertThat(it).isOk()
            }
        }

        // when
        val stats = callServiceRepeatedly(service = "service-2", repeat = 10, tag = "dolom,ipsum,lorem")

        // then
        assertThat(stats.totalHits).isEqualTo(10)
        assertThat(stats.hits(service2LoremIpsumDolomContainer)).isEqualTo(10)
        assertThat(stats.hits(service2LoremIpsumContainer)).isEqualTo(0)
        assertThat(stats.hits(service2DolomContainer)).isEqualTo(0)
    }

    @Test
    fun `should return 503 for request with three tags is service is not on the whitelist`() {
        // given
        registerServices()
        untilAsserted {
            callService("service-1").also {
                assertThat(it).isOk()
            }
        }

        // when
        val stats = callEchoServiceRepeatedly(repeat = 10, tag = "dolom,ipsum,lorem", assertNoErrors = false)

        // then
        assertThat(stats.totalHits).isEqualTo(10)
        assertThat(stats.failedHits).isEqualTo(10)
        assertThat(stats.hits(service1LoremIpsumContainer)).isEqualTo(0)
        assertThat(stats.hits(service1LoremContainer)).isEqualTo(0)
    }

    protected fun callEchoServiceRepeatedly(repeat: Int, tag: String? = null, assertNoErrors: Boolean = true): CallStats {
        return callServiceRepeatedly(
            service = "echo",
            repeat = repeat,
            tag = tag,
            assertNoErrors = assertNoErrors
        )
    }

    protected open fun callServiceRepeatedly(service: String, repeat: Int, tag: String? = null, assertNoErrors: Boolean = true): CallStats {
        val stats = CallStats()
        callServiceRepeatedly(
            service = service,
            stats = stats,
            minRepeat = repeat,
            maxRepeat = repeat,
            headers = tag?.let { mapOf("service-tag" to it) } ?: emptyMap(),
            assertNoErrors = assertNoErrors
        )
        return stats
    }

    class CallStats : CallStatistics {
        var failedHits: Int = 0
        var totalHits: Int = 0

        private val containers = listOf(regularContainer, loremContainer) + containersToStart
        private var containerHits: MutableMap<String, Int> = containers.associate { it.containerId to 0 }.toMutableMap()

        fun hits(container: EchoContainer) = containerHits[container.containerId] ?: 0

        override fun addResponse(response: ResponseWithBody) {
            regularContainer.containerId
            containers.firstOrNull { response.isFrom(it) }
                ?.let { containerHits.compute(it.containerId) { _, i -> i?.inc() } }
            if (!response.isOk()) failedHits++
            totalHits++
        }
    }
}
