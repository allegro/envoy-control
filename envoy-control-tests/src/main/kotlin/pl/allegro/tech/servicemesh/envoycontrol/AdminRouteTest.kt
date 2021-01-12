package pl.allegro.tech.servicemesh.envoycontrol

import okhttp3.Headers
import okhttp3.MediaType
import okhttp3.RequestBody
import okhttp3.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SecuredRoute
import java.util.stream.Stream

internal class AdminRouteTest {
    companion object {

        private val properties = mapOf(
            "envoy-control.envoy.snapshot.routes.admin.publicAccessEnabled" to true,
            "envoy-control.envoy.snapshot.routes.admin.path-prefix" to "/status/envoy",
            "envoy-control.envoy.snapshot.routes.admin.token" to "admin_secret_token",
            "envoy-control.envoy.snapshot.routes.admin.disable.on-header" to "to-disable",
            "envoy-control.envoy.snapshot.routes.admin.securedPaths" to listOf(
                SecuredRoute().apply {
                    pathPrefix = "/config_dump"
                    method = "GET"
                }
            )
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

        @JvmStatic
        fun disableOnHeaderTestCases(): Stream<Arguments> {
            val disableHeader = "to-disable" to ""

            return Stream.of(
                Arguments.of("admin root", {
                    envoy.ingressOperations.callLocalService(
                        endpoint = "/status/envoy/",
                        headers = Headers.of(mapOf(disableHeader))
                    )
                }),
                Arguments.of("admin root without trailing slash", {
                    envoy.ingressOperations.callLocalService(
                        endpoint = "/status/envoy",
                        headers = Headers.of(mapOf(disableHeader))
                    )
                }),
                Arguments.of("clusters", {
                    envoy.ingressOperations.callLocalService(
                        endpoint = "/status/envoy/clusters",
                        headers = Headers.of(mapOf(disableHeader))
                    )
                }),
                Arguments.of("config dump as unauthorized", {
                    envoy.ingressOperations.callLocalService(
                        endpoint = "/status/envoy/config_dump",
                        headers = Headers.of(mapOf(disableHeader))
                    )
                }),
                Arguments.of("config dump as authorized", {
                    envoy.ingressOperations.callLocalService(
                        endpoint = "/status/envoy/config_dump",
                        headers = Headers.of(mapOf(disableHeader, "authorization" to "admin_secret_token"))
                    )
                }),
                Arguments.of("reset counters as unauthorized", {
                    envoy.ingressOperations.callPostLocalService(
                        endpoint = "/status/envoy/reset_counters",
                        headers = Headers.of(mapOf(disableHeader)),
                        body = RequestBody.create(MediaType.get("application/json"), "{}")
                    )
                }),
                Arguments.of("reset counters as authorized", {
                    envoy.ingressOperations.callPostLocalService(
                        endpoint = "/status/envoy/reset_counters",
                        headers = Headers.of(mapOf(disableHeader, "authorization" to "admin_secret_token")),
                        body = RequestBody.create(MediaType.get("application/json"), "{}")
                    )
                })
            )
        }
    }

    @Test
    fun `should get admin redirected port on ingress port when enabled`() {
        // when
        val response = envoy.ingressOperations.callLocalService(endpoint = "/status/envoy", headers = Headers.of(emptyMap()))

        // then
        assertThat(response.isSuccessful).isTrue()
    }

    @Test
    fun `should get access to secured endpoints when authorized only`() {
        // when
        val configDumpResponseUnauthorized = envoy.ingressOperations.callLocalService(
            endpoint = "/status/envoy/config_dump",
            headers = Headers.of(emptyMap())
        )
        val configDumpResponseAuthorized = envoy.ingressOperations.callLocalService(
            endpoint = "/status/envoy/config_dump",
            headers = Headers.of(mapOf("authorization" to "admin_secret_token"))
        )
        val resetCountersResponseUnauthorized = envoy.ingressOperations.callPostLocalService(
            endpoint = "/status/envoy/reset_counters",
            headers = Headers.of(emptyMap()),
            body = RequestBody.create(MediaType.get("application/json"), "{}")
        )
        val resetCountersResponseAuthorized = envoy.ingressOperations.callPostLocalService(
            endpoint = "/status/envoy/reset_counters",
            headers = Headers.of(mapOf("authorization" to "admin_secret_token")),
            body = RequestBody.create(MediaType.get("application/json"), "{}")
        )

        // then
        assertThat(configDumpResponseUnauthorized.code()).isEqualTo(401)
        assertThat(configDumpResponseAuthorized.isSuccessful).isTrue()
        assertThat(resetCountersResponseUnauthorized.code()).isEqualTo(401)
        assertThat(resetCountersResponseAuthorized.isSuccessful).isTrue()
    }

    @ParameterizedTest
    @MethodSource("disableOnHeaderTestCases")
    fun `should block access to all admin endpoints when request contains the disable header`(
        caseDescription: String,
        request: () -> Response
    ) {
        // expect
        assertThat(request.invoke().code()).describedAs(caseDescription).isEqualTo(403)
    }
}
