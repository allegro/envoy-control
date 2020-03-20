package pl.allegro.tech.servicemesh.envoycontrol

import okhttp3.Headers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.config.Ads
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlRunnerTestApp
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration

internal class IncomingPermissionsTest : EnvoyControlTestConfiguration() {

    companion object {

        private val properties = mapOf(
            "envoy-control.envoy.snapshot.incoming-permissions.enabled" to true,
            "envoy-control.envoy.snapshot.incoming-permissions.sourceIpAuthentication.enabledForServices" to listOf("echo"),
            "envoy-control.envoy.snapshot.routes.status.create-virtual-cluster" to true,
            "envoy-control.envoy.snapshot.routes.status.path-prefix" to "/status/",
            "envoy-control.envoy.snapshot.routes.status.enabled" to true
        )

        @JvmStatic
        @BeforeAll
        fun setupTest() {
            setup(appFactoryForEc1 = { consulPort ->
                EnvoyControlRunnerTestApp(properties = properties, consulPort = consulPort)
            }, envoyConfig = Ads)
        }
    }

    @Test
    fun `should allow access to selected clients using source based authentication`() {
        registerService(name = "echo")

        untilAsserted {
            // when
            val response = callLocalService("/ip_endpoint", Headers.of())
            val statusUpstreamOk = envoyContainer1.admin().statValue(
                    "vhost.secured_local_service.vcluster.status.upstream_rq_200"
            )?.toInt()

            // then
            assertThat(response).isOk().isFrom(localServiceContainer)
            assertThat(statusUpstreamOk).isGreaterThan(0)
        }
    }

//    @Test
//    fun `should allow access to status endpoint by all clients`() {
//        untilAsserted {
//            // when
//            val response = callLocalService("/status/", Headers.of())
//            val statusUpstreamOk = envoyContainer1.admin().statValue(
//                    "vhost.secured_local_service.vcluster.status.upstream_rq_200"
//            )?.toInt()
//
//            // then
//            assertThat(response).isOk().isFrom(localServiceContainer)
//            assertThat(statusUpstreamOk).isGreaterThan(0)
//        }
//    }
//
//    @Test
//    fun `should allow access to endpoint by authorized client`() {
//        untilAsserted {
//            // when
//            val response = callLocalService(endpoint = "/endpoint?a=b",
//                headers = Headers.of(mapOf("x-service-name" to "authorizedClient")))
//
//            // then
//            assertThat(response).isOk().isFrom(localServiceContainer)
//        }
//    }
//
//    @Test
//    fun `should deny access to endpoint by unauthorized client`() {
//        untilAsserted {
//            // when
//            val response = callLocalService(endpoint = "/endpoint",
//                headers = Headers.of(mapOf("x-service-name" to "unuthorizedClient")))
//
//            // then
//            assertThat(response).isForbidden()
//        }
//    }
}
