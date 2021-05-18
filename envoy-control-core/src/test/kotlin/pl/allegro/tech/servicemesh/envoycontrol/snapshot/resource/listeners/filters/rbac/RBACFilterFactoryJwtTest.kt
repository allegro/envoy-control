package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters.rbac

import io.envoyproxy.controlplane.cache.SnapshotResources
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.groups.ClientWithSelector
import pl.allegro.tech.servicemesh.envoycontrol.groups.Incoming
import pl.allegro.tech.servicemesh.envoycontrol.groups.IncomingEndpoint
import pl.allegro.tech.servicemesh.envoycontrol.groups.OAuth
import pl.allegro.tech.servicemesh.envoycontrol.groups.PathMatchingType
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.GlobalSnapshot
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.IncomingPermissionsProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.JwtFilterProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.OAuthProvider
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.StatusRouteProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters.RBACFilterFactory

internal class RBACFilterFactoryJwtTest : RBACFilterFactoryTestUtils {
    private val rbacFilterFactoryWithOAuth = RBACFilterFactory(
        IncomingPermissionsProperties().also {
            it.enabled = true
            it.overlappingPathsFix = true
        },
        StatusRouteProperties(),
        jwtProperties = JwtFilterProperties().also {
            it.providers =
                mapOf("oauth-provider" to OAuthProvider("oauth-provider", selectorToTokenField = mapOf("oauth-selector" to "team1")))
        }
    )

    val snapshot = GlobalSnapshot(
        SnapshotResources.create(listOf(), ""),
        setOf(),
        SnapshotResources.create(listOf(), ""),
        mapOf(),
        SnapshotResources.create(listOf(), ""),
        SnapshotResources.create(listOf(), ""),
        SnapshotResources.create(listOf(), "")
    )

    @Test
    fun `should generate RBAC rules for OAuth`() {
        // given
        val selector = "oauth-selector"
        val client = "team1"
        val oAuthPrincipal = """{
                   "metadata": {
                    "filter": "envoy.filters.http.jwt_authn",
                    "path": [
                     {
                      "key": "jwt"
                     },
                     {
                      "key": "$selector"
                     }
                    ],
                    "value": {
                     "list_match": {
                      "one_of": {
                       "string_match": {
                        "exact": "$client"
                       }
                      }
                     }
                    }
                   }
                  }"""
        val expectedRbacBuilder = getRBACFilterWithShadowRules(
            expectedPoliciesForOAuthClient(oAuthPrincipal, selector, client),
            expectedPoliciesForOAuthClient(oAuthPrincipal, selector, client)
        )
        val incomingPermission = Incoming(
            permissionsEnabled = true,
            endpoints = listOf(
                IncomingEndpoint(
                    "/oauth-protected",
                    PathMatchingType.PATH,
                    setOf("GET"),
                    setOf(ClientWithSelector(client, selector)),
                    oauth = OAuth("oauth-provider", policy = OAuth.Policy.STRICT)
                )
            )
        )

        // when
        val generated = rbacFilterFactoryWithOAuth.createHttpFilter(createGroup(incomingPermission), snapshot)

        // then
        Assertions.assertThat(generated).isEqualTo(expectedRbacBuilder)
    }

    @Test
    fun `should not generate RBAC rules for JWT if no client with selector is defined`() {
        // given
        val incomingPermission = Incoming(
            permissionsEnabled = true,
            endpoints = listOf(
                IncomingEndpoint(
                    "/oauth-protected",
                    PathMatchingType.PATH,
                    setOf("GET"),
                    setOf(ClientWithSelector("client1")),
                    oauth = OAuth("oauth-provider", policy = OAuth.Policy.STRICT)
                )
            )
        )
        val expectedRbacBuilder = getRBACFilterWithShadowRules(
            expectedPoliciesForOAuthClient(authenticatedPrincipal("client1"), "null", "client1"),
            expectedPoliciesForOAuthClient(authenticatedPrincipal("client1"), "null", "client1")
        )

        // when
        val generated = rbacFilterFactoryWithOAuth.createHttpFilter(createGroup(incomingPermission), snapshot)

        // then
        Assertions.assertThat(generated).isEqualTo(expectedRbacBuilder)
    }

    private fun expectedPoliciesForOAuthClient(principals: String, selector: String, client: String) = """
        {
          "policies": {
            "IncomingEndpoint(path=/oauth-protected, pathMatchingType=PATH, methods=[GET], clients=[ClientWithSelector(name=$client, selector=$selector)], unlistedClientsPolicy=BLOCKANDLOG, oauth=OAuth(provider=oauth-provider, verification=OFFLINE, policy=STRICT))": {
              "permissions": [
                {
                  "and_rules": {
                    "rules": [
                      ${pathRule("/oauth-protected")},
                      {
                        "or_rules": {
                          "rules": [
                            ${methodRule("GET")}
                          ]
                        }
                      }
                    ]
                  }
                }
              ], "principals": [ $principals ]
            }
          }
        }
    """
}
