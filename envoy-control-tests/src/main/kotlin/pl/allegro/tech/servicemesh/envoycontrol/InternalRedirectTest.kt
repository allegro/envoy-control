package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isFrom
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isOk
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.echo.EchoServiceExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.echo.ServiceExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.redirect.RedirectServiceContainer
import java.net.UnknownHostException

class InternalRedirectTest {

    companion object {

        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(consul, mapOf(
            "envoy-control.envoy.snapshot.egress.handleInternalRedirect" to false
        ))

        @JvmField
        @RegisterExtension
        val service = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val envoy = EnvoyExtension(envoyControl, service)

        @JvmField
        @RegisterExtension
        val redirectService = ServiceExtension(RedirectServiceContainer(redirectTo = "service-1"))
    }

    @Test
    fun `redirect should be handled by envoy and response from service-1 is returned`() {
        // given
        consul.server.operations.registerService(service, name = "service-1")
        // service-redirect has defined in metadata redirect policy
        consul.server.operations.registerService(redirectService, name = "service-redirect")

        untilAsserted {
            // when
            val response = envoy.egressOperations.callService("service-redirect")

            // then
            assertThat(response).isOk().isFrom(service)
        }
    }

    @Test
    fun `envoy should not handle redirects by default`() {
        // given
        consul.server.operations.registerService(service, name = "service-1")
        consul.server.operations.registerService(redirectService, name = "service-5")

        untilAsserted {
            // when
            val exception = assertThrows<UnknownHostException> {
                envoy.egressOperations.callService("service-5")
            }

            // then
            assertThat(exception.message).contains("service-1")
        }
    }
}
