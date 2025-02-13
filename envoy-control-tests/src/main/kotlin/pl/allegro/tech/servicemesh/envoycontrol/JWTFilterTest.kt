package pl.allegro.tech.servicemesh.envoycontrol

import okhttp3.FormBody
import okhttp3.Headers.Companion.headersOf
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import pl.allegro.tech.discovery.consul.recipes.internal.http.MediaType
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isForbidden
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isFrom
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isOk
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo1EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo2EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.OAuthEnvoyConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.HttpResponseCloser.addToCloseableResponses
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.OAuthServerExtension
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.OAuthProvider
import java.net.URI
import java.util.stream.Stream

class JWTFilterTest {
    companion object {
        @JvmStatic
        fun provideClientsForTestWithNegatedSelector(): Stream<Arguments> {
            return Stream.of(
                Arguments.of("/allow-team1-deny-team2", "team1", true),
                Arguments.of("/allow-team1-deny-team2", "team2", false),
                Arguments.of("/allow-team1-deny-team2", "team3", false),
                Arguments.of("/allow-team1-deny-team2", "team1,team2", false),
                Arguments.of("/allow-team1-deny-team2", "team3,team2", false),
                Arguments.of("/non-team1-access", "team1", false),
                Arguments.of("/non-team1-access", "team2", true)
            )
        }

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
                "envoy-control.envoy.snapshot.outgoing-permissions.enabled" to true,
                "envoy-control.envoy.snapshot.incoming-permissions.enabled" to true,
                "envoy-control.envoy.snapshot.incoming-permissions.overlapping-paths-fix" to true,
                "envoy-control.envoy.snapshot.jwt.providers" to mapOf(
                    "first-provider" to OAuthProvider(
                        jwksUri = URI.create("http://oauth/first-provider/jwks"),
                        clusterName = "oauth",
                        matchings = mapOf("first-provider-prefix" to "authorities")
                    ),
                    "second-provider" to OAuthProvider(
                        jwksUri = URI.create(oAuthServer.getJwksAddress("second-provider")),
                        createCluster = true,
                        clusterName = "second-provider",
                        clusterPort = oAuthServer.container().oAuthPort(),
                        matchings = mapOf("second-provider-prefix" to "authorities")
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
                  outgoing:
                    dependencies:
                      - service: "oauth"
                  incoming:
                    unlistedEndpointsPolicy: blockAndLog
                    endpoints: 
                    - path: '/unprotected'
                      clients: ['echo2']
                      unlistedClientsPolicy: blockAndLog
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
                      clients: ['first-provider-prefix:team1', 'first-provider-prefix:team2','second-provider-prefix:team3']
                      unlistedClientsPolicy: blockAndLog
                      oauth:
                        provider: 'first-provider'
                        verification: offline
                        policy: strict
                    - path: '/oauth-or-tls'
                      clients: ['first-provider-prefix:team1', 'first-provider-prefix:team2', 'echo2']
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
                      clients: ['first-provider-prefix:team1']
                      unlistedClientsPolicy: blockAndLog
                    - path: '/non-team1-access'
                      clients: ['first-provider-prefix:!team1']
                      unlistedClientsPolicy: blockAndLog
                    - path: '/allow-team1-deny-team2'
                      clients: ['first-provider-prefix:team1','first-provider-prefix:!team2']
                      unlistedClientsPolicy: blockAndLog                    
                    - pathPrefix: '/no-clients'
                      clients: []
                      unlistedClientsPolicy: log
                      oauth:
                        provider: 'first-provider'
                        verification: offline
                        policy: strict
                    - path: '/log-with-clients'
                      clients: [some-service, other-service]
                      methods: [ GET ]
                      unlistedClientsPolicy: log
                      oauth:
                        provider: 'first-provider'
                        verification: offline
                        policy: strict
        """.trimIndent()
        )

        // language=yaml
        private val oauthConfig = """
            node:
              metadata:
                proxy_settings:
                  incoming:
                    unlistedEndpointsPolicy: log
                    endpoints: []
        """.trimIndent()

        @JvmField
        @RegisterExtension
        val oauthEnvoy = EnvoyExtension(
            envoyControl,
            oAuthServer,
            config = OAuthEnvoyConfig.copy(serviceName = "oauth", configOverride = oauthConfig)
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
        val echo2Envoy = EnvoyExtension(
            envoyControl,
            localService = service,
            config = Echo2EnvoyAuthConfig.copy(configOverride = echo2Config)
        )
    }

    @BeforeEach
    internal fun registerOAuthAndWait() {
        consul.server.operations.registerServiceWithEnvoyOnIngress(
            name = "oauth",
            extension = oauthEnvoy
        )
        envoy.waitForReadyServices("oauth")
    }

    @Test
    fun `should allow request without jwt for unprotected endpoint`() {

        // given
        registerEnvoyServiceAndWait()

        // when
        val response = echo2Envoy.egressOperations.callService(
            service = "echo",
            pathAndQuery = "/unprotected"
        )

        // then
        assertThat(response).isOk().isFrom(service)
    }

    @Test
    fun `should allow request with expired token for unprotected endpoint`() {
        // given
        val invalidToken = this::class.java.classLoader
            .getResource("oauth/invalid_jwks_token")!!.readText()
        registerEnvoyServiceAndWait()

        // when
        val response = echo2Envoy.egressOperations.callService(
            service = "echo",
            pathAndQuery = "/unprotected",
            headers = mapOf("Authorization" to "Bearer $invalidToken")
        )

        // then
        assertThat(response).isOk().isFrom(service)
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
            endpoint = "/rbac-clients-test", headers = headersOf("Authorization", "Bearer $token")
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
            endpoint = "/rbac-clients-test", headers = headersOf("Authorization", "Bearer $token")
        )
        val response2 = envoy.ingressOperations.callLocalService(
            endpoint = "/rbac-clients-test", headers = headersOf("Authorization", "Bearer $token2")
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
            endpoint = "/rbac-clients-test", headers = headersOf("Authorization", "Bearer $token")
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
            endpoint = "/rbac-clients-test", headers = headersOf("Authorization", "Bearer $token")
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
            endpoint = "/team-access", headers = headersOf("Authorization", "Bearer $token")
        )

        // then
        assertThat(response).isOk().isFrom(service)
    }

    @ParameterizedTest
    @MethodSource("provideClientsForTestWithNegatedSelector")
    fun `should allow only clients without negated selector`(endpoint: String, authority: String, isAllowed: Boolean) {

        // given
        registerClientWithAuthority("first-provider", authority, authority)
        val token = tokenForProvider("first-provider", authority)

        val response = envoy.ingressOperations.callLocalService(
            endpoint = endpoint, headers = headersOf("Authorization", "Bearer $token")
        )

        // then
        if (isAllowed) {
            assertThat(response).isOk()
        } else {
            assertThat(response).isForbidden()
        }
    }

    @Test
    fun `should allow request with token when policy is strict, unlisted clients policy is log and there are no clients`() {
        // given
        val token = tokenForProvider("first-provider")

        // when
        val response = envoy.ingressOperations.callLocalService(
            endpoint = "/no-clients", headers = headersOf("Authorization", "Bearer $token")
        )

        // then
        assertThat(response).isOk().isFrom(service)
    }

    @Test
    fun `should allow request  with token from unlisted client when policy is strict, unlisted clients policy is log and there are other clients defined`() {
        // given
        val token = tokenForProvider("first-provider")

        // when
        val response = envoy.ingressOperations.callLocalService(
            endpoint = "/log-with-clients", headers = headersOf("Authorization", "Bearer $token")
        )

        // then
        assertThat(response).isOk().isFrom(service)
    }

    @Test
    fun `should reject request with wrong token when policy is strict, unlisted clients policy is log and there are no clients`() {
        // given
        val token = tokenForProvider("wrong-provider")

        // when
        val response = envoy.ingressOperations.callLocalService(
            endpoint = "/no-clients", headers = headersOf("Authorization", "Bearer $token")
        )

        // then
        assertThat(response).isForbidden()
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
        OkHttpClient().newCall(
            Request.Builder().post(FormBody.Builder().add("client_id", clientId).build())
                .url(oAuthServer.getTokenAddress(provider)).build()
        )
            .execute().addToCloseableResponses()
            .body!!.string()

    private fun registerClientWithAuthority(provider: String, clientId: String, authority: String) {
        val body = """{
            "clientId": "$clientId",
            "clientSecret": "secret",
             "authorities":["$authority"]
        }"""
        return OkHttpClient().newCall(
            Request.Builder().put(body.toRequestBody(MediaType.JSON_MEDIA_TYPE))
                .url("http://localhost:${oAuthServer.container().port()}/$provider/client").build()
        )
            .execute().close()
    }
}
