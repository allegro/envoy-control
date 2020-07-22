package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlRunnerTestApp
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration

internal class HealthIndicatorTest : EnvoyControlTestConfiguration() {

    companion object {

        private val properties = mapOf(
            "management.endpoint.health.show-details" to "ALWAYS"
        )

        @JvmStatic
        @BeforeAll
        fun setupTest() {
            setup(appFactoryForEc1 = { consulPort -> EnvoyControlRunnerTestApp(properties, consulPort) })
        }
    }

    @Test
    fun `should application state be healthy after state of applications is loaded from consul`() {
        untilAsserted {
            // when
            val responseState = envoyControl1.getState()
            val healthStatus = envoyControl1.getHealthStatus()

            // then
            assertThat(healthStatus).isStatusHealthy().hasEnvoyControlCheckPassed()
            assertThat(responseState).hasServiceStateChanged()
        }
    }
}
