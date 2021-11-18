package pl.allegro.tech.servicemesh.envoycontrol

import okhttp3.Headers.Companion.headersOf
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isOk
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension

class LocalServiceTest {

    companion object {
        private const val prefix = "envoy-control.envoy.snapshot"

        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(
            consul, mapOf(
                "$prefix.ingress.add-service-name-header-to-response" to true,
                "$prefix.ingress.add-requested-authority-header-to-response" to true
            )
        )

        @JvmField
        @RegisterExtension
        val service = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val envoy = EnvoyExtension(envoyControl, service)
    }

    @Test
    fun `should add header with service name to response`() {
        // given
        consul.server.operations.registerService(service, name = "service-1")

        // when
        untilAsserted {
            // when
            val response = envoy.ingressOperations.callLocalService("/", headers = headersOf("host", "test-service"))

            assertThat(response).isOk()
            assertThat(response.header("x-service-name")).isEqualTo("echo2")
            assertThat(response.header("x-requested-authority")).isEqualTo("test-service")
        }
    }
}
