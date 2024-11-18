package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isFrom
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isOk
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.CustomRuteProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.StringMatcher
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.StringMatcherType

internal class CustomRouteTest {
    companion object {

        private val properties = mapOf(
            "envoy-control.envoy.snapshot.routes.customs" to listOf(CustomRuteProperties().apply {
                enabled = true
                cluster = "wrapper"
                path = StringMatcher().apply {
                    type = StringMatcherType.PREFIX
                    value = "/status/wrapper/"
                }
            }),
        )

        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(consul, properties)

        @JvmField
        @RegisterExtension
        val service = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val wrapper = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        // val envoy = EnvoyExtension(envoyControl, service)
        val envoy = EnvoyExtension(envoyControl, service, wrapperService = wrapper)
    }
    @Test
    fun `should redirect to wrapper`() {
        // when
        val response = envoy.ingressOperations.callLocalService(
            endpoint = "/status/wrapper/prometheus"
        )
        // then
        assertThat(response).isOk()
            .isFrom(wrapper)
    }
}
