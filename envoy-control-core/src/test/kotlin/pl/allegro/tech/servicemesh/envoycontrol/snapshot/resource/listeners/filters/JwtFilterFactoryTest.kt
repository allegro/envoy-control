package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters

import com.google.protobuf.Any
import com.google.protobuf.util.JsonFormat
import io.envoyproxy.envoy.extensions.filters.http.jwt_authn.v3.JwtAuthentication
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.groups.CommunicationMode
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group
import pl.allegro.tech.servicemesh.envoycontrol.groups.Incoming
import pl.allegro.tech.servicemesh.envoycontrol.groups.IncomingEndpoint
import pl.allegro.tech.servicemesh.envoycontrol.groups.OAuth
import pl.allegro.tech.servicemesh.envoycontrol.groups.ProxySettings
import pl.allegro.tech.servicemesh.envoycontrol.groups.ServicesGroup
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.JwtFilterProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.OAuthProvider
import java.net.URI

internal class JwtFilterFactoryTest {
    private val jwtFilterFactory = JwtFilterFactory(
        JwtFilterProperties().also {
            it.forwardJwt = true
            it.providers = mapOf(
                "provider" to OAuthProvider(URI.create("http://provider/jwks"), "provider-cluster")
            )
        }
    )
    private val multiProviderJwtFilterFactory = JwtFilterFactory(
        JwtFilterProperties().also {
            it.forwardJwt = true
            it.providers = mapOf(
                "provider1" to OAuthProvider(URI.create("http://provider1/jwks"), "provider1-cluster"),
                "provider2" to OAuthProvider(URI.create("http://provider2/jwks"), "provider2-cluster")
            )
        }
    )
    private val noProviderJwtFilterFactory = JwtFilterFactory(JwtFilterProperties().also { it.providers = emptyMap() })

    private val emptyGroup: Group = ServicesGroup(CommunicationMode.ADS)

    @Test
    fun `should not create JWT filter when no providers are defined`() {
        // given
        val group: Group = createGroup(mapOf("/" to "oauth-provider"), OAuth.Policy.STRICT)

        // when
        val filter = noProviderJwtFilterFactory.createJwtFilter(group)

        // then
        assertThat(filter).isNull()
    }

    @Test
    fun `should not create JWT filter when group contains no endpoint with oauth`() {
        // when
        val filter = jwtFilterFactory.createJwtFilter(emptyGroup)

        // then
        assertThat(filter).isNull()
    }

    @Test
    fun `should create JWT filter with allow missing or failed policy when OAuth is defined in group`() {
        // given
        val group = createGroup(mapOf("/" to "provider"), OAuth.Policy.STRICT)
        val expectedJwtFilter = getJwtFilter(
            singleProviderJson(
                """ 
            "requires": {
                "requiresAny": {
                    "requirements": [{"providerName": "provider"}, {"allowMissingOrFailed": {} }]
                 }
            }"""
            )
        )

        // when
        val generatedFilter = jwtFilterFactory.createJwtFilter(group)

        // then
        assertThat(generatedFilter).isNotNull
        assertThat(generatedFilter).isEqualTo(expectedJwtFilter)
    }

    @Test
    fun `should create JWT filter with multiple providers`() {
        // given
        val group = createGroup(
            mapOf(
                "/provider1-protected" to "provider1",
                "/provider2-protected" to "provider2"
            ),
            OAuth.Policy.ALLOW_MISSING_OR_FAILED
        )
        val expectedJwtFilter = getJwtFilter(multiProviderJson)

        // when
        val generatedFilter = multiProviderJwtFilterFactory.createJwtFilter(group)

        // then
        assertThat(generatedFilter).isNotNull
        assertThat(generatedFilter).isEqualTo(expectedJwtFilter)
    }

    private fun getJwtFilter(providersJson: String): HttpFilter? {
        val jwt = JwtAuthentication.newBuilder()
        JsonFormat.parser().merge(providersJson, jwt)
        return HttpFilter.newBuilder()
            .setName("envoy.filters.http.jwt_authn")
            .setTypedConfig(
                Any.pack(
                    jwt.build()
                )
            )
            .build()
    }

    private fun createGroup(pathToProvider: Map<String, String>, policy: OAuth.Policy) = ServicesGroup(
        CommunicationMode.ADS, proxySettings = ProxySettings(
            Incoming(
                pathToProvider.map { (path, provider) ->
                    IncomingEndpoint(
                        path,
                        oauth = OAuth(provider, policy = policy)
                    )
                }
            )
        )
    )

    private fun singleProviderJson(requires: String) = """{
  "providers": {
    "provider": {
      "remoteJwks": {
        "httpUri": {
          "uri": "http://provider/jwks",
          "cluster": "provider-cluster",
          "timeout": "1s"
        },
        "cacheDuration": "300s"
      },
      "forward": true,
      "forwardPayloadHeader": "x-oauth-token-validated",
      "payloadInMetadata": "jwt"
    }
  },
  "rules": [{
    "match": {
      "prefix": "/"
    },
    $requires
  }]
}"""

    private val multiProviderJson = """{
  "providers": {
    "provider1": {
      "remoteJwks": {
        "httpUri": {
          "uri": "http://provider1/jwks",
          "cluster": "provider1-cluster",
          "timeout": "1s"
        },
        "cacheDuration": "300s"
      },
      "forward": true,
      "forwardPayloadHeader": "x-oauth-token-validated",
      "payloadInMetadata": "jwt"
    },
    "provider2": {
      "remoteJwks": {
        "httpUri": {
          "uri": "http://provider2/jwks",
          "cluster": "provider2-cluster",
          "timeout": "1s"
        },
        "cacheDuration": "300s"
      },
      "forward": true,
      "forwardPayloadHeader": "x-oauth-token-validated",
      "payloadInMetadata": "jwt"
    }
  },
  "rules": [{
    "match": {
      "prefix": "/provider1-protected"
    },
    "requires": {
                "requiresAny": {
                    "requirements": [{"providerName": "provider1"}, {"allowMissingOrFailed": {} }]
                 }
            }
  }, {
    "match": {
      "prefix": "/provider2-protected"
    },
    "requires": {
                "requiresAny": {
                    "requirements": [{"providerName": "provider2"}, {"allowMissingOrFailed": {} }]
                 }
            }
  }]
}"""
}
