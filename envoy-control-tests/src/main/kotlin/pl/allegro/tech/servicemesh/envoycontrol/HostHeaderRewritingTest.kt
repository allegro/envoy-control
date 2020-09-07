package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlRunnerTestApp
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration
import pl.allegro.tech.servicemesh.envoycontrol.config.service.HttpsEchoContainer

internal class HostHeaderRewritingTest : EnvoyControlTestConfiguration() {

    companion object {
        const val customHostHeader = "x-envoy-original-host-test"
        val httpsEchoContainer = HttpsEchoContainer()

        @JvmStatic
        @BeforeAll
        fun setupTest() {
            httpsEchoContainer.start()
            setup(
                appFactoryForEc1 = { consulPort ->
                    EnvoyControlRunnerTestApp(
                        consulPort = consulPort, properties = mapOf(
                            "envoy-control.envoy.snapshot.egress.host-header-rewriting.custom-host-header" to customHostHeader,
                            "envoy-control.envoy.snapshot.egress.host-header-rewriting.enabled" to true
                        )
                    )
                }
            )
        }

        @JvmStatic
        @AfterAll
        fun teardown() {
            httpsEchoContainer.stop()
        }
    }

    @Test
    fun `should override Host header with value from specified custom header`() {
        // given
        registerService(name = "host-rewrite-service", container = httpsEchoContainer, port = HttpsEchoContainer.PORT)

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
    fun `should not override Host header when target service has host-header-rewriting disabled`() {
        // given
        registerService(name = "service-1", container = httpsEchoContainer, port = HttpsEchoContainer.PORT)

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
