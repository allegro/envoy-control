package pl.allegro.tech.servicemesh.envoycontrol

import okhttp3.Headers
import okhttp3.MediaType
import okhttp3.RequestBody
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlRunnerTestApp
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SecuredRoute

internal class AdminRouteTest : EnvoyControlTestConfiguration() {
    companion object {

        private val properties = mapOf(
            "envoy-control.envoy.snapshot.routes.admin.publicAccessEnabled" to true,
            "envoy-control.envoy.snapshot.routes.admin.path-prefix" to "/status/envoy",
            "envoy-control.envoy.snapshot.routes.admin.token" to "admin_secret_token",
            "envoy-control.envoy.snapshot.routes.admin.securedPaths" to listOf(
                SecuredRoute().apply {
                    pathPrefix = "/config_dump"
                    method = "GET"
                }
            )
        )

        @JvmStatic
        @BeforeAll
        fun setupAdminRoutesTest() {
            setup(appFactoryForEc1 = { consulPort -> EnvoyControlRunnerTestApp(properties, consulPort) })
        }
    }

    @Test
    fun `should get admin redirected port on ingress port when enabled`() {
        // given
        localServiceContainer.stop()

        // when
        val response = callLocalService(endpoint = "/status/envoy", headers = Headers.of(emptyMap()))

        // then
        assertThat(response.isSuccessful).isTrue()
    }

    @Test
    fun `should get access to secured endpoints when authorized only`() {
        // given
        localServiceContainer.stop()

        // when
        val configDumpResponseUnauthorized = callLocalService(
            endpoint = "/status/envoy/config_dump",
            headers = Headers.of(emptyMap())
        )
        val configDumpResponseAuthorized = callLocalService(
            endpoint = "/status/envoy/config_dump",
            headers = Headers.of(mapOf("authorization" to "admin_secret_token"))
        )
        val resetCountersResponseUnauthorized = callPostLocalService(
            endpoint = "/status/envoy/reset_counters",
            headers = Headers.of(emptyMap()),
            body = RequestBody.create(MediaType.get("application/json"), "{}")
        )
        val resetCountersResponseAuthorized = callPostLocalService(
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
}
