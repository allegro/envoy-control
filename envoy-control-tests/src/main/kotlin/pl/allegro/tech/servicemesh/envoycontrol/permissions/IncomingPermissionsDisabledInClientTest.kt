package pl.allegro.tech.servicemesh.envoycontrol.permissions

import okhttp3.Headers.Companion.toHeaders
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isFrom
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isOk
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.AdsWithDisabledEndpointPermissions
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension

internal class IncomingPermissionsDisabledInClientTest {

    companion object {

        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(
            consul, mapOf(
                "envoy-control.envoy.snapshot.incoming-permissions.enabled" to true,
                "envoy-control.envoy.snapshot.incoming-permissions.overlapping-paths-fix" to true
            )
        )

        @JvmField
        @RegisterExtension
        val service = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val envoy = EnvoyExtension(envoyControl, localService = service, config = AdsWithDisabledEndpointPermissions)
    }

    @Test
    fun `should allow access to endpoint by any client when endpoint permissions not defined in client`() {
        untilAsserted {
            // when
            val response = envoy.ingressOperations.callLocalService(
                "/",
                headers = mapOf("x-service-name" to "any").toHeaders()
            )

            // then
            assertThat(response).isOk().isFrom(service)
        }
    }
}
