package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration

internal class OriginalDestinationTest : EnvoyControlTestConfiguration() {

    companion object {

        @JvmStatic
        @BeforeAll
        fun setupTest() {
            setup()
        }
    }

    @Test
    fun `should send direct request when host envoy-original-destination and header x-envoy-original-dst-host with IP provided`() {
        untilAsserted {
            // when
            val response = callServiceWithOriginalDst(
                echoContainer.address(),
                envoyContainer.egressListenerUrl()
            )

            // then
            assertThat(response).isOk().isFrom(echoContainer)
        }
    }
}
