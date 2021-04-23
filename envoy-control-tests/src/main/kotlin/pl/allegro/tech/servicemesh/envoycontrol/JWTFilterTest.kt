package pl.allegro.tech.servicemesh.envoycontrol

import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isFrom
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isOk
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isUnauthorized
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo1EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.OAuthServerExtension
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.OAuthProvider
import java.net.URI

class JWTFilterTest {
    companion object {

        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        @JvmField
        @RegisterExtension
        val oAuthServer = OAuthServerExtension()

        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(
            consul,
            mapOf(
                "envoy-control.envoy.snapshot.incoming-permissions.enabled" to true,
                "envoy-control.envoy.snapshot.incoming-permissions.overlapping-paths-fix" to true,
                "envoy-control.envoy.snapshot.jwt.providers" to listOf(
                    OAuthProvider(
                        name = "first-provider",
                        jwksUri = URI.create(oAuthServer.getJwksAddress("first-provider")),
                        clusterName = "first-provider",
                        clusterPort = oAuthServer.container().oAuthPort()
                    ),
                    OAuthProvider(
                        name = "second-provider",
                        jwksUri = URI.create(oAuthServer.getJwksAddress("second-provider")),
                        clusterName = "second-provider",
                        clusterPort = oAuthServer.container().oAuthPort()
                    )
                )
            )
        )

        @JvmField
        @RegisterExtension
        val service = EchoServiceExtension()

        // language=yaml
        private val echoConfig = Echo1EnvoyAuthConfig.copy(
            configOverride = """
            node:
              metadata:
                proxy_settings:
                  incoming:
                    unlistedEndpointsPolicy: log
                    endpoints: 
                    - path: '/first-provider-protected'
                      clients: []
                      unlistedClientsPolicy: log
                      oauth:
                        provider: 'first-provider'
                        verification: offline
                        policy: strict
                    - path: '/second-provider-protected'
                      clients: []
                      unlistedClientsPolicy: log
                      oauth:
                        provider: 'second-provider'
                        verification: offline
                        policy: strict
        """.trimIndent()
        )

        @JvmField
        @RegisterExtension
        val envoy = EnvoyExtension(envoyControl, service, echoConfig)
    }

    @Test
    fun `should reject request without jwt`() {

        // when
        val response = envoy.ingressOperations.callLocalService(
            endpoint = "/first-provider-protected"
        )

        // then
        assertThat(response).isUnauthorized()
    }

    @Test
    fun `should allow request with valid jwt`() {

        // given
        val token = tokenForProvider("first-provider")

        // when
        val response = envoy.ingressOperations.callLocalService(
            endpoint = "/first-provider-protected", headers = Headers.of("Authorization", "Bearer $token")
        )

        // then
        assertThat(response).isOk().isFrom(service)
    }

    @Test
    fun `should reject request with expired Token`() {

        // given
        val invalidToken = this::class.java.classLoader
            .getResource("oauth/invalid_jwks_token")!!.readText()

        // when
        val response = envoy.ingressOperations.callLocalService(
            endpoint = "/first-provider-protected", headers = Headers.of("Authorization", "Bearer $invalidToken")
        )

        // then
        assertThat(response).isUnauthorized()
    }

    @Test
    fun `should reject request with token from wrong provider`() {

        // given
        val token = tokenForProvider("wrong-provider")

        // when
        val response = envoy.ingressOperations.callLocalService(
            endpoint = "/first-provider-protected", headers = Headers.of("Authorization", "Bearer $token")
        )

        // then
        assertThat(response).isUnauthorized()
    }

    @Test
    fun `should allow requests with valid jwt when many providers are defined`() {

        // given
        val firstProviderToken = tokenForProvider("first-provider")
        val secondProviderToken = tokenForProvider("second-provider")

        // when
        val firstProviderResponse = envoy.ingressOperations.callLocalService(
            endpoint = "/first-provider-protected", headers = Headers.of("Authorization", "Bearer $firstProviderToken")
        )
        val secondProviderResponse = envoy.ingressOperations.callLocalService(
            endpoint = "/second-provider-protected", headers = Headers.of("Authorization", "Bearer $secondProviderToken")
        )

        // then
        assertThat(firstProviderResponse).isOk()
        assertThat(secondProviderResponse).isOk()
    }

    private fun tokenForProvider(provider: String) =
        OkHttpClient().newCall(Request.Builder().get().url(oAuthServer.getTokenAddress(provider)).build())
            .execute()
            .body()!!.string()
}
