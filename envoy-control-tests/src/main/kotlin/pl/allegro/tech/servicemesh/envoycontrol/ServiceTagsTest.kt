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
            "envoy-control.envoy.snapshot.load-balancing.canary.enabled" to false
        )

        @JvmStatic
        @BeforeAll
        fun setupTest() {
            setup(appFactoryForEc1 = { consulPort ->
                EnvoyControlRunnerTestApp(properties = properties, consulPort = consulPort)
            })
        }

        private val regularContainer = echoContainer
        private val loremContainer = echoContainer2
        private val loremIpsumContainer = EchoContainer().also { it.start() }
        private val service1LoremContainer = EchoContainer().also { it.start() }
        private val service1LoremIpsumContainer = EchoContainer().also { it.start() }
        private val service2LoremIpsumContainer = EchoContainer().also { it.start() }
        private val service2LoremIpsumDolomContainer = EchoContainer().also { it.start() }

        @JvmStatic
        @AfterAll
        fun cleanup() {
            loremIpsumContainer.stop()
            service1LoremContainer.stop()
            service1LoremIpsumContainer.stop()
            service2LoremIpsumContainer.stop()
            service2LoremIpsumDolomContainer.stop()
        }
    }

    protected fun registerServices() {
        registerService(name = "echo", container = regularContainer, tags = listOf())
        registerService(name = "echo", container = loremContainer, tags = listOf("lorem"))
        registerService(name = "echo", container = loremIpsumContainer, tags = listOf("lorem", "ipsum"))
        registerService(name = "service-1", container = service1LoremContainer, tags = listOf("lorem"))
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
        assertThat(stats.regularHits).isEqualTo(0)
        assertThat(stats.loremHits).isEqualTo(0)
        assertThat(stats.loremIpsumHits).isEqualTo(10)
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
        assertThat(stats.regularHits).isEqualTo(0)
        assertThat(stats.loremHits).isGreaterThan(2)
        assertThat(stats.loremIpsumHits).isGreaterThan(2)
        assertThat(stats.loremHits + stats.loremIpsumHits).isEqualTo(20)
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
        assertThat(stats.regularHits).isGreaterThan(1)
        assertThat(stats.loremHits).isGreaterThan(1)
        assertThat(stats.loremIpsumHits).isGreaterThan(1)
        assertThat(stats.regularHits + stats.loremHits + stats.loremIpsumHits).isEqualTo(20)
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
        assertThat(stats.regularHits).isEqualTo(0)
        assertThat(stats.loremHits).isEqualTo(0)
        assertThat(stats.loremIpsumHits).isEqualTo(0)
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
        assertThat(stats.service1LoremIpsumHits).isEqualTo(10)
        assertThat(stats.service1LoremHits).isEqualTo(0)
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
        assertThat(stats.regularHits).isEqualTo(0)
        assertThat(stats.loremHits).isEqualTo(0)
        assertThat(stats.loremIpsumHits).isEqualTo(0)
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
        assertThat(stats.service2LoremIpsumDolomHits).isEqualTo(10)
        assertThat(stats.service2LoremIpsumHits).isEqualTo(0)
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
        assertThat(stats.service1LoremIpsumHits).isEqualTo(0)
        assertThat(stats.service1LoremHits).isEqualTo(0)
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

    class CallStats(
        var regularHits: Int = 0,
        var loremHits: Int = 0,
        var loremIpsumHits: Int = 0,
        var totalHits: Int = 0,
        var service1LoremHits: Int = 0,
        var service1LoremIpsumHits: Int = 0,
        var service2LoremIpsumHits: Int = 0,
        var service2LoremIpsumDolomHits: Int = 0,
        var failedHits: Int = 0
    ) : CallStatistics {
        override fun addResponse(response: ResponseWithBody) {
            if (response.isFrom(regularContainer)) regularHits++
            if (response.isFrom(loremContainer)) loremHits++
            if (response.isFrom(loremIpsumContainer)) loremIpsumHits++
            if (response.isFrom(service1LoremContainer)) service1LoremHits++
            if (response.isFrom(service1LoremIpsumContainer)) service1LoremIpsumHits++
            if (response.isFrom(service2LoremIpsumContainer)) service2LoremIpsumHits++
            if (response.isFrom(service2LoremIpsumDolomContainer)) service2LoremIpsumDolomHits++
            if (!response.isOk()) failedHits++
            totalHits++
        }
    }
}
