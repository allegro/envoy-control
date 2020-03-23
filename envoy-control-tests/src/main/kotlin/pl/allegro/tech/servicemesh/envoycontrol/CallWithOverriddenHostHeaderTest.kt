package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlRunnerTestApp
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration
import pl.allegro.tech.servicemesh.envoycontrol.config.containers.HttpBinContainer

internal class CallWithOverriddenHostHeaderTest : EnvoyControlTestConfiguration() {

    companion object {
        @JvmStatic
        @BeforeAll
        fun setupTest() {
            setup(appFactoryForEc1 = { consulPort ->
                EnvoyControlRunnerTestApp(consulPort = consulPort)
            })
        }
    }

    @Test
    fun `should override Host header with value from specified custom header`() {
        // given
        registerService(name = "service-1", container = httpBinContainer, port = HttpBinContainer.PORT)

        untilAsserted {
            // when
            val response = callService(service = "service-1", pathAndQuery = "/headers", headers = mapOf("Custom-Host-Test" to "some-original-host"))

            // then
            assertThat(response).isOk().hasOverriddenHostHeaderWithValue("some-original-host")
        }
    }
}
