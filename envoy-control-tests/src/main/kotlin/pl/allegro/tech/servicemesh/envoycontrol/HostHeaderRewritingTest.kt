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
import pl.allegro.tech.servicemesh.envoycontrol.config.service.GenericServiceExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.HttpsEchoContainer

class HostHeaderRewritingTest {

    companion object {
        const val customHostHeader = "x-envoy-original-host-test"

        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(consul, mapOf(
            "envoy-control.envoy.snapshot.egress.host-header-rewriting.custom-host-header" to customHostHeader,
            "envoy-control.envoy.snapshot.egress.host-header-rewriting.enabled" to true
        ))

        @JvmField
        @RegisterExtension
        val service = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val httpsService = GenericServiceExtension(HttpsEchoContainer())

        @JvmField
        @RegisterExtension
        val envoy = EnvoyExtension(envoyControl)
    }

    @Test
    fun `should override Host header with value from specified custom header`() {
        // given
        consul.server.operations.registerService(httpsService, name = "host-rewrite-service")

        untilAsserted {
            // when
            val response = envoy.egressOperations.callService(
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
        consul.server.operations.registerService(httpsService, name = "service-1")

        untilAsserted {
            // when
            val response = envoy.egressOperations.callService(
                service = "service-1",
                pathAndQuery = "/headers",
                headers = mapOf(customHostHeader to "some-original-host")
            )

            // then
            assertThat(response).isOk().hasHostHeaderWithValue("service-1")
        }
    }
}

fun ObjectAssert<Response>.hasHostHeaderWithValue(overriddenHostHeader: String): ObjectAssert<Response> {
    matches({
        it.body()?.use { it.string().contains("\"host\": \"$overriddenHostHeader\"") } ?: false
    }, "Header Host should be overridden with value: $overriddenHostHeader")
    return this
}
