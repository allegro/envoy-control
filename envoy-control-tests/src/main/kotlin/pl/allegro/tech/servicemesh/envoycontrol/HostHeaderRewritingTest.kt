package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlRunnerTestApp
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration
import pl.allegro.tech.servicemesh.envoycontrol.config.containers.HttpBinContainer

internal class HostHeaderRewritingTest : EnvoyControlTestConfiguration() {

    companion object {
        const val customHostHeader = "x-destination-host"

        @JvmStatic
        @BeforeAll
        fun setupTest() {
            setup(
                appFactoryForEc1 = { consulPort ->
                    EnvoyControlRunnerTestApp(
                        consulPort = consulPort, properties = mapOf(
                            "envoy-control.envoy.snapshot.host-header-rewriting.custom-host-header" to customHostHeader,
                            "envoy-control.envoy.snapshot.host-header-rewriting.enabled" to true
                        )
                    )
                }
            )
        }
    }

    @Test
    fun `should override Host header with value from specified custom header`() {
        // given
        registerService(name = "host-rewrite-service", container = httpBinContainer, port = HttpBinContainer.PORT)

        untilAsserted {
            // when
            val response = callService(
                service = "host-rewrite-service",
                pathAndQuery = "/headers",
                headers = mapOf(customHostHeader to "some-original-host")
            )

            // then
            assertThat(response).isOk().hasHostHeaderWithValue("some-original-host")
        }
    }

    @Test
    fun `should not override Host header while service is not whitelisted`() {
        // given
        registerService(name = "service-1", container = httpBinContainer, port = HttpBinContainer.PORT)

        untilAsserted {
            // when
            val response = callService(
                service = "service-1",
                pathAndQuery = "/headers",
                headers = mapOf(customHostHeader to "some-original-host")
            )

            // then
            assertThat(response).isOk().hasHostHeaderWithValue("service-1")
        }
    }
}
