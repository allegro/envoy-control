package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlRunnerTestApp
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration

class RegexServicesFilterTest : EnvoyControlTestConfiguration() {

    companion object {

        private val properties = mapOf(
            "envoy-control.service-filters.excluded-names-patterns" to ".*-[1-2]$".toRegex()
        )

        @JvmStatic
        @BeforeAll
        fun setupTest() {
            setup(appFactoryForEc1 = { consulPort -> EnvoyControlRunnerTestApp(properties, consulPort) })
        }
    }

    @Test
    fun `should not reach service whose name ends with number from 1 to 4`() {
        // given
        registerService(name = "service-1", container = echoContainer)
        registerService(name = "service-2", container = echoContainer)
        registerService(name = "service-3", container = echoContainer)

        untilAsserted {
            // when
            val response1 = callService("service-1")
            val response2 = callService("service-2")
            val response3 = callService("service-3")

            // then
            assertThat(response1).isUnreachable()
            assertThat(response2).isUnreachable()
            assertThat(response3).isOk().isFrom(echoContainer)
        }
    }
}
