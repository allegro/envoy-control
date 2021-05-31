package pl.allegro.tech.servicemesh.envoycontrol

import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.discovery.consul.recipes.internal.http.MediaType
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isForbidden
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isFrom
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isOk
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo1EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo2EnvoyAuthConfig
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
                "envoy-control.envoy.snapshot.jwt.providers" to mapOf(
                    "first-provider" to OAuthProvider(
                        issuer = "first-provider",
                        jwksUri = URI.create(oAuthServer.getJwksAddress("first-provider")),
                        clusterName = "first-provider",
                        clusterPort = oAuthServer.container().oAuthPort(),
                        selectorToTokenField = mapOf("oauth-selector" to "authorities")
                    ),
                    "second-provider" to OAuthProvider(
                        issuer = "second-provider",
                        jwksUri = URI.create(oAuthServer.getJwksAddress("second-provider")),
                        clusterName = "second-provider",
                        clusterPort = oAuthServer.container().oAuthPort(),
                        selectorToTokenField = mapOf("second-selector" to "authorities")

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
                    unlistedEndpointsPolicy: blockAndLog
                    endpoints: 
                    - path: '/first-provider-protected'
                      clients: ['echo2']
                      unlistedClientsPolicy: blockAndLog
                      oauth:
                        provider: 'first-provider'
                        verification: offline
                        policy: strict
                    - path: '/second-provider-protected'
                      clients: ['echo2']
                      unlistedClientsPolicy: blockAndLog
                      oauth:
                        provider: 'second-provider'
                        verification: offline
                        policy: strict
                    - path: '/rbac-clients-test'
                      clients: ['team1:oauth-selector', 'team2:oauth-selector','team3:second-selector']
                      unlistedClientsPolicy: blockAndLog
                      oauth:
                        provider: 'first-provider'
                        verification: offline
                        policy: strict
                    - path: '/oauth-or-tls'
                      clients: ['team1:oauth-selector', 'team2:oauth-selector', 'echo2']
                      unlistedClientsPolicy: blockAndLog
                      oauth:
                        provider: 'first-provider'
                        verification: offline
                        policy: allowMissing
                    - path: '/first-provider-allow-missing-or-failed'
                      clients: ['echo2']
                      unlistedClientsPolicy: blockAndLog
                      oauth:
                        provider: 'first-provider'
                        verification: offline
                        policy: allowMissingOrFailed
                    - path: '/team-access'
                      clients: ['team1:oauth-selector']
                      unlistedClientsPolicy: blockAndLog
        """.trimIndent()
        )

        @JvmField
        @RegisterExtension
        val envoy = EnvoyExtension(envoyControl, service, echoConfig)

        // language=yaml
        private val echo2Config = """
            node:
              metadata:
                proxy_settings:
                  outgoing:
                    dependencies:
                      - service: "echo"
        """.trimIndent()

        @JvmField
        @RegisterExtension
        val echo2Envoy = EnvoyExtension(envoyControl, localService = service, config = Echo2EnvoyAuthConfig.copy(configOverride = echo2Config))
    }

    @Test
    fun `should reject request without jwt`() {
        // given
        registerEnvoyServiceAndWait()

        // when
        val response = echo2Envoy.egressOperations.callService(
            service = "echo",
            pathAndQuery = "/first-provider-protected"
        )

        // then
        assertThat(response).isForbidden()
    }

    @Test
    fun `should allow request with valid jwt`() {

        // given
        val token = tokenForProvider("first-provider")
        registerEnvoyServiceAndWait()

        // when
        val response = echo2Envoy.egressOperations.callService(
            service = "echo",
            pathAndQuery = "/first-provider-protected",
            headers = mapOf("Authorization" to "Bearer $token")
        )

        // then
        assertThat(response).isOk().isFrom(service)
    }

    @Test
    fun `should reject request with expired Token`() {

        // given
        val invalidToken = this::class.java.classLoader
            .getResource("oauth/invalid_jwks_token")!!.readText()
        registerEnvoyServiceAndWait()

        // when
        val response = echo2Envoy.egressOperations.callService(
            service = "echo",
            pathAndQuery = "/first-provider-protected",
            headers = mapOf("Authorization" to "Bearer $invalidToken")
        )

        // then
        assertThat(response).isForbidden()
    }

    @Test
    fun `should reject request with token from wrong provider`() {

        // given
        val token = tokenForProvider("wrong-provider")
        registerEnvoyServiceAndWait()

        // when
        val response = echo2Envoy.egressOperations.callService(
            service = "echo",
            pathAndQuery = "/first-provider-protected",
            headers = mapOf("Authorization" to "Bearer $token")
        )

        // then
        assertThat(response).isForbidden()
    }

    @Test
    fun `should allow requests with valid jwt when many providers are defined`() {

        // given
        val firstProviderToken = tokenForProvider("first-provider")
        val secondProviderToken = tokenForProvider("second-provider")
        registerEnvoyServiceAndWait()

        // when
        val firstProviderResponse = echo2Envoy.egressOperations.callService(
            service = "echo",
            pathAndQuery = "/first-provider-protected",
            headers = mapOf("Authorization" to "Bearer $firstProviderToken")
        )
        val secondProviderResponse = echo2Envoy.egressOperations.callService(
            service = "echo",
            pathAndQuery = "/second-provider-protected",
            headers = mapOf("Authorization" to "Bearer $secondProviderToken")
        )

        // then
        assertThat(firstProviderResponse).isOk()
        assertThat(secondProviderResponse).isOk()
    }

    @Test
    fun `should reject access to endpoint with client having OAuth selector if token does not have necessary claims`() {

        // given
        registerClientWithAuthority("first-provider", "unauthorized-client", "wrong-team")
        val token = tokenForProvider("first-provider", "unauthorized-client")

        // when
        val response = envoy.ingressOperations.callLocalService(
            endpoint = "/rbac-clients-test", headers = Headers.of("Authorization", "Bearer $token")
        )

        // then
        assertThat(response).isForbidden()
    }

    @Test
    fun `should allow requests to endpoint with client having OAuth selector if token has necessary claims`() {

        // given
        registerClientWithAuthority("first-provider", "client1-rbac", "team1")
        registerClientWithAuthority("first-provider", "client2-rbac", "team2")

        val token = tokenForProvider("first-provider", "client1-rbac")
        val token2 = tokenForProvider("first-provider", "client2-rbac")

        // when
        val response = envoy.ingressOperations.callLocalService(
            endpoint = "/rbac-clients-test", headers = Headers.of("Authorization", "Bearer $token")
        )
        val response2 = envoy.ingressOperations.callLocalService(
            endpoint = "/rbac-clients-test", headers = Headers.of("Authorization", "Bearer $token2")
        )

        // then
        assertThat(response).isOk().isFrom(service)
        assertThat(response2).isOk().isFrom(service)
    }

    @Test
    fun `should allow request to endpoint with client having OAuth selector from other provider if token has necessary claims`() {

        // given
        registerClientWithAuthority("second-provider", "client-rbac", "team3")
        val token = tokenForProvider("second-provider", "client-rbac")

        // when
        val response = envoy.ingressOperations.callLocalService(
            endpoint = "/rbac-clients-test", headers = Headers.of("Authorization", "Bearer $token")
        )

        // then
        assertThat(response).isOk().isFrom(service)
    }

    @Test
    fun `should allow request with valid token when policy is allow missing`() {

        // given
        val token = tokenForProvider("first-provider")
        registerEnvoyServiceAndWait()

        // when
        val echoResponse = echo2Envoy.egressOperations.callService(
            service = "echo",
            pathAndQuery = "/oauth-or-tls",
            headers = mapOf("Authorization" to "Bearer $token")

        )

        // then
        assertThat(echoResponse).isOk()
    }

    @Test
    fun `should allow client with listed name and no token to access endpoint when oauth policy is allowMissing`() {

        // given
        registerEnvoyServiceAndWait()

        // when
        val echoResponse = echo2Envoy.egressOperations.callService(
            service = "echo",
            pathAndQuery = "/oauth-or-tls"
        )

        // then
        assertThat(echoResponse).isOk().isFrom(service)
    }

    @Test
    fun `should reject request with wrong token when policy is allow missing`() {

        // given
        val token = tokenForProvider("wrong-provider")
        registerEnvoyServiceAndWait()

        // when
        val echoResponse = echo2Envoy.egressOperations.callService(
            service = "echo",
            pathAndQuery = "/oauth-or-tls",
            headers = mapOf("Authorization" to "Bearer $token")

        )

        // then
        assertThat(echoResponse).isForbidden()
    }

    @Test
    fun `should reject request with valid token when unlistedClientsPolicy is blockAndLog`() {
        // given
        val token = tokenForProvider("first-provider")

        // when
        val response = envoy.ingressOperations.callLocalService(
                endpoint = "/rbac-clients-test", headers = Headers.of("Authorization", "Bearer $token")
        )

        // then
        assertThat(response).isForbidden()
    }

    @Test
    fun `should allow request with wrong token when policy is allowMissingOrFailed`() {

        // given
        val token = tokenForProvider("wrong-provider")
        registerEnvoyServiceAndWait()

        // when
        val echoResponse = echo2Envoy.egressOperations.callService(
            service = "echo",
            pathAndQuery = "/first-provider-allow-missing-or-failed",
            headers = mapOf("Authorization" to "Bearer $token")

        )

        // then
        assertThat(echoResponse).isFrom(service).isOk()
    }

    @Test
    fun `should allow client with oauth selector when oauth is not specified for given endpoint`() {

        // given
        registerClientWithAuthority("first-provider", "client1-rbac", "team1")
        val token = tokenForProvider("first-provider", "client1-rbac")

        // when
        val response = envoy.ingressOperations.callLocalService(
            endpoint = "/team-access", headers = Headers.of("Authorization", "Bearer $token")
        )

        // then
        assertThat(response).isOk().isFrom(service)
    }

    private fun registerEnvoyServiceAndWait() {
        consul.server.operations.registerServiceWithEnvoyOnIngress(
            name = "echo",
            extension = envoy,
            tags = listOf("mtls:enabled")
        )
        echo2Envoy.waitForAvailableEndpoints("echo")
    }

    private fun tokenForProvider(provider: String, clientId: String = "client1") =
        OkHttpClient().newCall(Request.Builder().post(FormBody.Builder().add("client_id", clientId).build()).url(oAuthServer.getTokenAddress(provider)).build())
            .execute()
            .body()!!.string()

    private fun registerClientWithAuthority(provider: String, clientId: String, authority: String): Response {
        val body = """{
            "clientId": "$clientId",
            "clientSecret": "secret",
             "authorities":["$authority"]
        }"""
        return OkHttpClient().newCall(Request.Builder().put(RequestBody.create(MediaType.JSON_MEDIA_TYPE, body)).url("http://localhost:${oAuthServer.container().port()}/$provider/client").build())
            .execute()
    }
}
