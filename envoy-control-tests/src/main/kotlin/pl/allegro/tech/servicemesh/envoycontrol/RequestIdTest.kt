package pl.allegro.tech.servicemesh.envoycontrol

import okhttp3.Headers.Companion.toHeaders
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isOk
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.GenericServiceExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.HttpsEchoContainer
import pl.allegro.tech.servicemesh.envoycontrol.config.service.asHttpsEchoResponse

class RequestIdTest {

    companion object {
        @JvmStatic
        fun extraHeadersSource() = listOf(
            emptyMap(),
            mapOf("x-forwarded-for" to "123.321.231.111"),
            mapOf("x-forwarded-for" to "111.111.222.222,123.123.231.231")
        )

        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(consul)

        @JvmField
        @RegisterExtension
        val localService = GenericServiceExtension(HttpsEchoContainer())

        @JvmField
        @RegisterExtension
        val envoy = EnvoyExtension(envoyControl, localService)

        @JvmField
        @RegisterExtension
        val externalService = GenericServiceExtension(HttpsEchoContainer())
    }

    @BeforeEach
    fun setup() {
        consul.server.operations.registerService(externalService, name = "service-1")
    }

    @ParameterizedTest
    @MethodSource("extraHeadersSource")
    fun `should propagate x-request-id on the egress port when it is available in request`(extraHeaders: Map<String, String>) {
        // given
        val requestIdHeader = mapOf("x-request-id" to "egress-fake-request-id")

        untilAsserted {
            // when
            val response = envoy.egressOperations
                .callService(service = "service-1", headers = requestIdHeader + extraHeaders)
                .asHttpsEchoResponse()

            // then
            assertThat(response).isOk()
            assertThat(response.requestHeaders).containsEntry("x-request-id", "egress-fake-request-id")
        }
    }

    @ParameterizedTest
    @MethodSource("extraHeadersSource")
    fun `should generate x-request-id on the egress port when it is missing in request`(extraHeaders: Map<String, String>) {
        untilAsserted {
            // when
            val response = envoy.egressOperations
                .callService(service = "service-1", headers = extraHeaders)
                .asHttpsEchoResponse()

            // then
            assertThat(response).isOk()
            assertThat(response.requestHeaders).hasEntrySatisfying("x-request-id") { assertThat(it).isNotBlank() }
        }
    }

    @ParameterizedTest
    @MethodSource("extraHeadersSource")
    fun `should propagate x-request-id on the ingress port when it is available in request`(extraHeaders: Map<String, String>) {
        // given
        val requestIdHeader = mapOf("x-request-id" to "ingress-fake-request-id")

        untilAsserted {
            // when
            val response = envoy.ingressOperations
                .callLocalService(endpoint = "/", headers = (requestIdHeader + extraHeaders).toHeaders())
                .asHttpsEchoResponse()

            // then
            assertThat(response).isOk()
            assertThat(response.requestHeaders).containsEntry("x-request-id", "ingress-fake-request-id")
        }
    }

    @ParameterizedTest
    @MethodSource("extraHeadersSource")
    fun `should generate x-request-id on the ingress port when it is missing in request`(extraHeaders: Map<String, String>) {
        untilAsserted {
            // when
            val response = envoy.ingressOperations
                .callLocalService(endpoint = "/", headers = extraHeaders.toHeaders())
                .asHttpsEchoResponse()

            // then
            assertThat(response).isOk()
            assertThat(response.requestHeaders).hasEntrySatisfying("x-request-id") { assertThat(it).isNotBlank() }
        }
    }
}
