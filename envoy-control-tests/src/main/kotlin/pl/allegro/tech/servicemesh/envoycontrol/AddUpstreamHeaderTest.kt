package pl.allegro.tech.servicemesh.envoycontrol

import okhttp3.Response
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.ObjectAssert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isOk
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension

class AddUpstreamHeaderTest {

    companion object {
        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(consul)

        @JvmField
        @RegisterExtension
        val envoy = EnvoyExtension(envoyControl)

        @JvmField
        @RegisterExtension
        val echoContainer = EchoServiceExtension()
    }

    @Test
    fun `should add x-envoy-upstream-remote-address header with address of upstream service`() {
        // given
        consul.server.operations.registerService(echoContainer, name = "service-1")

        untilAsserted {
            // when
            val response = envoy.egressOperations.callService(service = "service-1", pathAndQuery = "/endpoint")

            // then
            assertThat(response).isOk().hasXEnvoyUpstreamRemoteAddressFrom(echoContainer)
        }
    }

    private fun ObjectAssert<Response>.hasXEnvoyUpstreamRemoteAddressFrom(
        echoServiceExtension: EchoServiceExtension
    ): ObjectAssert<Response> {
        matches {
            it
                .headers("x-envoy-upstream-remote-address")
                .contains(echoServiceExtension.container().address())
        }
        return this
    }
}
