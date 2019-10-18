package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlRunnerTestApp
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration
import pl.allegro.tech.servicemesh.envoycontrol.config.echo.EchoContainer

open class ServiceTagsTest : EnvoyControlTestConfiguration() {

    companion object {
        private val properties = mapOf(
//            "envoy-control.envoy.snapshot.egress.load-balancing.weights.enabled" to true,
//            "envoy-control.envoy.snapshot.egress.load-balancing.canary.enabled" to true,

            "envoy-control.envoy.snapshot.egress.routing.service-tags.enabled" to true,
            "envoy-control.envoy.snapshot.egress.routing.service-tags.metadata-key" to "tag"
        )

        private val regularContainer = echoContainer
        private val loremContainer = echoContainer2
        private val loremIpsumContainer = EchoContainer().also { it.start() }

        @JvmStatic
        @BeforeAll
        fun setupTest() {
            setup(appFactoryForEc1 = { consulPort ->
                EnvoyControlRunnerTestApp(properties = properties, consulPort = consulPort)
            })

            registerService(name = "echo", container = regularContainer, tags = listOf())
            registerService(name = "echo", container = loremContainer, tags = listOf("lorem"))
            registerService(name = "echo", container = loremIpsumContainer, tags = listOf("lorem", "ipsum"))
        }
    }

    @Test
    fun `should route requests to instance with tag ipsum`() {
        // given
        untilAsserted {
            callService("echo").also {
                assertThat(it).isOk()
            }
        }
        val stats = CallStats()

        // when
        callServiceRepeatedly(
            service = "echo",
            stats = stats,
            minRepeat = 10,
            maxRepeat = 10,
            headers = mapOf("service-tag" to "ipsum")
        )

        // then
        assertThat(stats.totalHits).isEqualTo(10)
        assertThat(stats.regularHits).isEqualTo(0)
        assertThat(stats.loremHits).isEqualTo(0)
        assertThat(stats.loremIpsumHits).isEqualTo(10)
    }

    @Test
    fun `should route requests to instances with tag lorem`() {
        // given
        untilAsserted {
            callService("echo").also {
                assertThat(it).isOk()
            }
        }
        val stats = CallStats()

        // when
        callServiceRepeatedly(
            service = "echo",
            stats = stats,
            minRepeat = 20,
            maxRepeat = 20,
            headers = mapOf("service-tag" to "lorem")
        )

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
        untilAsserted {
            callService("echo").also {
                assertThat(it).isOk()
            }
        }
        val stats = CallStats()

        // when
        callServiceRepeatedly(
            service = "echo",
            stats = stats,
            minRepeat = 20,
            maxRepeat = 20
        )

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
        untilAsserted {
            callService("echo").also {
                assertThat(it).isOk()
            }
        }
        val stats = CallStats()

        // when
        callServiceRepeatedly(
            service = "echo",
            stats = stats,
            minRepeat = 10,
            maxRepeat = 10,
            headers = mapOf("service-tag" to "dolom"),
            assertNoErrors = false
        )

        // then
        assertThat(stats.totalHits).isEqualTo(10)
        assertThat(stats.failedHits).isEqualTo(10)
        assertThat(stats.regularHits).isEqualTo(0)
        assertThat(stats.loremHits).isEqualTo(0)
        assertThat(stats.loremIpsumHits).isEqualTo(0)
    }


    @AfterEach
    override fun cleanupTest() {
        // do not deregister services
    }

    inner class CallStats(
        var regularHits: Int = 0,
        var loremHits: Int = 0,
        var loremIpsumHits: Int = 0,
        var totalHits: Int = 0,
        var failedHits: Int = 0
    ) : CallStatistics {
        override fun addResponse(response: ResponseWithBody) {
            if (response.isFrom(regularContainer)) regularHits++
            if (response.isFrom(loremContainer)) loremHits++
            if (response.isFrom(loremIpsumContainer)) loremIpsumHits++
            if (!response.isOk()) failedHits++
            totalHits ++
        }
    }
}