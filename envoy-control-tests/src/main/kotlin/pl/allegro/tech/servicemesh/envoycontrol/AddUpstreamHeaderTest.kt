package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlRunnerTestApp
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration

internal class AddUpstreamHeaderTest : EnvoyControlTestConfiguration() {

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
    fun `should add x-envoy-upstream-remote-address header with address of upstream service`() {
        // given
        registerService(name = "service-1")

        untilAsserted {
            // when
            val response = callService(service = "service-1", pathAndQuery = "/endpoint")

            // then
            assertThat(response).isOk().hasXEnvoyUpstreamRemoteAddressFrom(echoContainer)
        }
    }
}
