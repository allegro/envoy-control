package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.testcontainers.containers.ContainerLaunchException
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlRunnerTestApp
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration

internal class SnapshotUpdaterBadConfigTest : EnvoyControlTestConfiguration() {
    companion object {
        private val properties = mapOf(
            "envoy-control.envoy.snapshot.incoming-permissions.enabled" to true
        )

        @JvmStatic
        @BeforeAll
        fun setupTest() {
            setup(appFactoryForEc1 = { consulPort -> EnvoyControlRunnerTestApp(properties, consulPort) })
        }
    }

    @Test
    fun `should not crash on a badly configured client`() {
        // given
        val envoyWithFaultyConfig = createEnvoyContainerWithFaultyConfig()

        val id = registerService(name = "echo")
        untilAsserted {
            // when
            val response = callEcho()

            // then
            assertThat(response).isOk().isFrom(echoContainer)
        }

        assertThrows<ContainerLaunchException> {
            envoyWithFaultyConfig.start()
        }

        checkTrafficRoutedToSecondInstance(id)
    }
}
