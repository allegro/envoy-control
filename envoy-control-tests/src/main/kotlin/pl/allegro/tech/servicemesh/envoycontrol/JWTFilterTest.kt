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
                        name = "oauth2-mock",
                        jwksUri = URI.create(oAuthServer.getJwksAddress()),
                        clusterName = "oauth",
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
                    - path: /jwt-protected
                      clients: []
                      unlistedClientsPolicy: log
                      oauth:
                        provider: oauth2-mock
                        verification: offline
                        policy: strict
        """.trimIndent()
        )

        @JvmField
        @RegisterExtension
        val envoy = EnvoyExtension(envoyControl, service, echoConfig)
    }

    @Test
    fun `should not allow requests without jwt`() {

        // when
        val response = envoy.ingressOperations.callLocalService(
            endpoint = "/jwt-protected"
        )

        // then
        assertThat(response).isUnauthorized()
    }

    @Test
    fun `should allow requests with valid jwt`() {

        // given
        val token = OkHttpClient().newCall(Request.Builder().get().url(oAuthServer.getTokenAddress()).build()).execute()
            .body()!!.string()

        // when
        val response = envoy.ingressOperations.callLocalService(
            endpoint = "/jwt-protected", headers = Headers.of("Authorization", "Bearer $token")
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
            endpoint = "/jwt-protected", headers = Headers.of("Authorization", "Bearer $invalidToken")
        )

        // then
        assertThat(response).isUnauthorized()
    }
}
