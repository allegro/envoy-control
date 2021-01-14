package pl.allegro.tech.servicemesh.envoycontrol.permissions

import okhttp3.Headers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isFrom
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isOk
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.EndpointMatch

internal class StatusRouteIncomingPermissionsTest {

    companion object {

        private val properties = mapOf(
            "envoy-control.envoy.snapshot.incoming-permissions.enabled" to true,
            "envoy-control.envoy.snapshot.incoming-permissions.overlapping-paths-fix" to true,
            "envoy-control.envoy.snapshot.routes.status.create-virtual-cluster" to true,
            "envoy-control.envoy.snapshot.routes.status.endpoints" to mutableListOf(EndpointMatch().also {
                it.path = "/status/"
            }),
            "envoy-control.envoy.snapshot.routes.status.enabled" to true
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
        val envoy = EnvoyExtension(envoyControl, service)
    }

    @Test
    fun `should allow access to status endpoint by all clients`() {
        untilAsserted {
            // when
            val response = envoy.ingressOperations.callLocalService("/status/", Headers.of())
            val statusUpstreamOk = envoy.container.admin().statValue(
                "vhost.secured_local_service.vcluster.status.upstream_rq_200"
            )?.toInt()

            // then
            assertThat(response).isOk().isFrom(service)
            assertThat(statusUpstreamOk).isGreaterThan(0)
        }
    }
}
