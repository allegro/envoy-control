package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlRunnerTestApp
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration
import pl.allegro.tech.servicemesh.envoycontrol.config.containers.EchoContainer

open class ServiceTagsTest : EnvoyControlTestConfiguration() {

    companion object {
        private val properties = mapOf(
            "envoy-control.envoy.snapshot.routing.service-tags.enabled" to true,
            "envoy-control.envoy.snapshot.routing.service-tags.metadata-key" to "tag"
        )

        @JvmStatic
        @BeforeAll
        fun setupTest() {
            setup(appFactoryForEc1 = { consulPort ->
                EnvoyControlRunnerTestApp(properties = properties, consulPort = consulPort)
            })
        }
    }

    protected open val loremTag = "lorem"
    protected open val ipsumTag = "ipsum"

    private val regularContainer = echoContainer
    private val loremContainer = echoContainer2
    private val loremIpsumContainer = EchoContainer().also { it.start() }

    protected fun registerServices() {
        registerService(name = "echo", container = regularContainer, tags = listOf())
        registerService(name = "echo", container = loremContainer, tags = listOf(loremTag))
        registerService(name = "echo", container = loremIpsumContainer, tags = listOf(loremTag, ipsumTag))
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
        val stats = callEchoServiceRepeatedly(repeat = 10, tag = ipsumTag)

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
        val stats = callEchoServiceRepeatedly(repeat = 20, tag = loremTag)

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

    open protected fun callEchoServiceRepeatedly(repeat: Int, tag: String? = null, assertNoErrors: Boolean = true): CallStats {
        val stats = CallStats()
        callServiceRepeatedly(
            service = "echo",
            stats = stats,
            minRepeat = repeat,
            maxRepeat = repeat,
            headers = tag?.let { mapOf("service-tag" to it) } ?: emptyMap(),
            assertNoErrors = assertNoErrors
        )
        return stats
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