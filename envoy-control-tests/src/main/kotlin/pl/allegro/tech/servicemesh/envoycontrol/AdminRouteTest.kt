package pl.allegro.tech.servicemesh.envoycontrol

import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType.Companion.toMediaType
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
                        headers = mapOf(disableHeader).toHeaders()
                    )
                }),
                Arguments.of("admin root without trailing slash", {
                    envoy.ingressOperations.callLocalService(
                        endpoint = "/status/envoy",
                        headers = mapOf(disableHeader).toHeaders()
                    )
                }),
                Arguments.of("clusters", {
                    envoy.ingressOperations.callLocalService(
                        endpoint = "/status/envoy/clusters",
                        headers = mapOf(disableHeader).toHeaders()
                    )
                }),
                Arguments.of("config dump as unauthorized", {
                    envoy.ingressOperations.callLocalService(
                        endpoint = "/status/envoy/config_dump",
                        headers = mapOf(disableHeader).toHeaders()
                    )
                }),
                Arguments.of("config dump as authorized", {
                    envoy.ingressOperations.callLocalService(
                        endpoint = "/status/envoy/config_dump",
                        headers = mapOf(disableHeader, "authorization" to "admin_secret_token").toHeaders()
                    )
                }),
                Arguments.of("reset counters as unauthorized", {
                    envoy.ingressOperations.callPostLocalService(
                        endpoint = "/status/envoy/reset_counters",
                        headers = mapOf(disableHeader).toHeaders(),
                        body = RequestBody.create("application/json".toMediaType(), "{}")
                    )
                }),
                Arguments.of("reset counters as authorized", {
                    envoy.ingressOperations.callPostLocalService(
                        endpoint = "/status/envoy/reset_counters",
                        headers = mapOf(disableHeader, "authorization" to "admin_secret_token").toHeaders(),
                        body = RequestBody.create("application/json".toMediaType(), "{}")
                    )
                })
            )
        }
    }

    @Test
    fun `should get admin redirected port on ingress port when enabled`() {
        // when
        val response = envoy.ingressOperations.callLocalService(
            endpoint = "/status/envoy",
            headers = emptyMap<String, String>().toHeaders()
        )

        // then
        assertThat(response.isSuccessful).isTrue()
    }

    @Test
    fun `should get access to secured endpoints when authorized only`() {
        // when
        val configDumpResponseUnauthorized = envoy.ingressOperations.callLocalService(
            endpoint = "/status/envoy/config_dump",
            headers = emptyMap<String, String>().toHeaders()
        )
        val configDumpResponseAuthorized = envoy.ingressOperations.callLocalService(
            endpoint = "/status/envoy/config_dump",
            headers = mapOf("authorization" to "admin_secret_token").toHeaders()
        )
        val resetCountersResponseUnauthorized = envoy.ingressOperations.callPostLocalService(
            endpoint = "/status/envoy/reset_counters",
            headers = emptyMap<String, String>().toHeaders(),
            body = RequestBody.create("application/json".toMediaType(), "{}")
        )
        val resetCountersResponseAuthorized = envoy.ingressOperations.callPostLocalService(
            endpoint = "/status/envoy/reset_counters",
            headers = mapOf("authorization" to "admin_secret_token").toHeaders(),
            body = RequestBody.create("application/json".toMediaType(), "{}")
        )

        // then
        assertThat(configDumpResponseUnauthorized.code).isEqualTo(401)
        assertThat(configDumpResponseAuthorized.isSuccessful).isTrue()
        assertThat(resetCountersResponseUnauthorized.code).isEqualTo(401)
        assertThat(resetCountersResponseAuthorized.isSuccessful).isTrue()
    }

    @ParameterizedTest
    @MethodSource("disableOnHeaderTestCases")
    fun `should block access to all admin endpoints when request contains the disable header`(
        caseDescription: String,
        request: () -> Response
    ) {
        // expect
        assertThat(request.invoke().code).describedAs(caseDescription).isEqualTo(403)
    }
}
