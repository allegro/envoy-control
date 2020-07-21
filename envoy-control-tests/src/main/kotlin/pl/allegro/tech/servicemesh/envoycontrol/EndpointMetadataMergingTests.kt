package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlRunnerTestApp
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.CallStats

open class EndpointMetadataMergingTests : EnvoyControlTestConfiguration() {

    companion object {
        private val properties = mapOf(
            "envoy-control.envoy.snapshot.routing.service-tags.enabled" to true
        )

        @JvmStatic
        @BeforeAll
        fun setupTest() {
            setup(appFactoryForEc1 = { consulPort ->
                EnvoyControlRunnerTestApp(properties = properties, consulPort = consulPort)
            })
        }
    }

    @Test
    fun `should merge all service tags of endpoints with the same ip and port`() {
        // given
        registerService(name = "echo", container = echoContainer, tags = listOf("ipsum"))
        registerService(name = "echo", container = echoContainer, tags = listOf("lorem", "dolom"))

        waitForReadyServices("echo")

        // when
        val ipsumStats = callEchoServiceRepeatedly(repeat = 1, tag = "ipsum")
        val loremStats = callEchoServiceRepeatedly(repeat = 1, tag = "lorem")
        val dolomStats = callEchoServiceRepeatedly(repeat = 1, tag = "dolom")

        // then
        assertThat(ipsumStats.hits(echoContainer)).isEqualTo(1)
        assertThat(loremStats.hits(echoContainer)).isEqualTo(1)
        assertThat(dolomStats.hits(echoContainer)).isEqualTo(1)
    }

    protected open fun callEchoServiceRepeatedly(
        repeat: Int,
        tag: String? = null,
        assertNoErrors: Boolean = true
    ): CallStats {
        val stats = CallStats(listOf(echoContainer))
        callServiceRepeatedly(
            service = "echo",
            stats = stats,
            minRepeat = repeat,
            maxRepeat = repeat,
            headers = tag?.let { mapOf("x-service-tag" to it) } ?: emptyMap(),
            assertNoErrors = assertNoErrors
        )
        return stats
    }
}
