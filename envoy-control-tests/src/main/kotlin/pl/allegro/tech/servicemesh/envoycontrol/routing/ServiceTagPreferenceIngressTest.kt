package pl.allegro.tech.servicemesh.envoycontrol.routing

import okhttp3.Headers.Companion.headersOf
import okhttp3.Headers.Companion.toHeaders
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.HttpsEchoExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.asHttpsEchoResponse

class ServiceTagPreferenceIngressTest {

    companion object {
        @RegisterExtension
        val consul = ConsulExtension()

        @RegisterExtension
        val envoyControl = EnvoyControlExtension(
            consul = consul, mapOf(
                "envoy-control.envoy.snapshot.routing.service-tags.enabled" to true,
                "envoy-control.envoy.snapshot.routing.service-tags.preference-routing.header" to "x-service-tag-preference",
                "envoy-control.envoy.snapshot.routing.service-tags.preference-routing.default-preference-env" to "DEFAULT_SERVICE_TAG_PREFERENCE",
                "envoy-control.envoy.snapshot.routing.service-tags.preference-routing.default-preference-fallback" to "global",
                "envoy-control.envoy.snapshot.routing.service-tags.preference-routing.enable-for-all" to true,
            )
        )

        @RegisterExtension
        val localService = HttpsEchoExtension()

        @RegisterExtension
        val envoyVte22 = EnvoyExtension(envoyControl = envoyControl, localService = localService).also {
            it.container.withEnv("DEFAULT_SERVICE_TAG_PREFERENCE", "vte22|global")
        }
    }

    @Test
    fun `should pass request x-service-tag-preference if it's more specific`() {
        envoyVte22.ingressOperations.callLocalService(
            endpoint = "/",
            headers = mapOf("x-service-tag-preference" to "lvte-1|vte22|global").toHeaders()
        ).asHttpsEchoResponse().let {
            assertThat(it.requestHeaders).containsEntry("x-service-tag-preference", "lvte-1|vte22|global")
        }
    }

    @Test
    fun `should pass default service tag preference if it's more specific than the request one`() {
        envoyVte22.ingressOperations.callLocalService(
            endpoint = "/",
            headers = mapOf("x-service-tag-preference" to "global").toHeaders()
        ).asHttpsEchoResponse().let {
            assertThat(it.requestHeaders).containsEntry("x-service-tag-preference", "vte22|global")
        }
    }

    @Test
    fun `should pass default service tag preference if request preference is absent`() {
        envoyVte22.ingressOperations.callLocalService(
            endpoint = "/",
            headers = headersOf()
        ).asHttpsEchoResponse().let {
            assertThat(it.requestHeaders).containsEntry("x-service-tag-preference", "vte22|global")
        }
    }
}
