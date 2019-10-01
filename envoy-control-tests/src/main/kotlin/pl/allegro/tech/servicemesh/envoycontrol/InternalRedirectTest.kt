package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlRunnerTestApp
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration
import pl.allegro.tech.servicemesh.envoycontrol.config.redirect.RedirectServiceContainer
import java.net.UnknownHostException

internal class InternalRedirectTest : EnvoyControlTestConfiguration() {

    companion object {
        private val redirectServiceContainer = RedirectServiceContainer(redirectTo = "service-1")
        private val properties = mapOf(
            "envoy-control.envoy.snapshot.egress.handleInternalRedirect" to false
        )

        @JvmStatic
        @BeforeAll
        fun setupTest() {
            setup(appFactoryForEc1 = { consulPort ->
                EnvoyControlRunnerTestApp(properties = properties, consulPort = consulPort)
            })
            redirectServiceContainer.start()
        }
    }

    @Test
    fun `redirect should be handled by envoy and response from service-1 is returned`() {
        // given
        registerService(name = "service-1")
        // service-redirect has defined in metadata redirect policy
        registerRedirectService(serviceName = "service-redirect")

        untilAsserted {
            // when
            val response = call(host = "service-redirect")

            // then
            assertThat(response).isOk().isFrom(echoContainer)
        }
    }

    @Test
    fun `envoy should not handle redirect`() {
        // given
        registerService(name = "service-1")
        registerRedirectService(serviceName = "service-5")

        untilAsserted {
            // when
            val exception = assertThrows<UnknownHostException> { call(host = "service-5") }

            // then
            assertThat(exception.message).contains("service-1: nodename nor servname provided, or not known")
        }
    }

    private fun registerRedirectService(serviceName: String) {
        registerService(name = serviceName, container = redirectServiceContainer, port = RedirectServiceContainer.PORT)
    }
}
