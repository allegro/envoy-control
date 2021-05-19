package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters.rbac

import io.envoyproxy.controlplane.cache.SnapshotResources
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
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
                mapOf(
                    "oauth-provider" to OAuthProvider(
                        "oauth-provider",
                        selectorToTokenField = mapOf("oauth-selector" to "team1")
                    )
                )
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

    @ParameterizedTest
    @EnumSource(OAuth.Policy::class)
    fun `should generate RBAC rules for OAuth Policy`(policy: OAuth.Policy) {
        // given
        val oAuthPolicyPrincipal = principalForOAuthPolicy(policy)

        val expectedRbacBuilder = getRBACFilterWithShadowRules(
            expectedPoliciesForOAuth(
                oAuthPolicyPrincipal,
                "",
                "OAuth(provider=oauth-provider, verification=OFFLINE, policy=$policy)"
            ),
            expectedPoliciesForOAuth(
                oAuthPolicyPrincipal,
                "",
                "OAuth(provider=oauth-provider, verification=OFFLINE, policy=$policy)"
            )
        )

        val incomingPermission = Incoming(
            permissionsEnabled = true,
            endpoints = listOf(
                IncomingEndpoint(
                    "/oauth-protected",
                    PathMatchingType.PATH,
                    setOf("GET"),
                    setOf(),
                    oauth = OAuth("oauth-provider", policy = policy)
                )
            )
        )

        // when
        val generated = rbacFilterFactoryWithOAuth.createHttpFilter(createGroup(incomingPermission), snapshot)

        // then
        Assertions.assertThat(generated).isEqualTo(expectedRbacBuilder)
    }

    @Test
    fun `should generate RBAC rules for Clients with OAuth selectors`() {
        // given
        val selector = "oauth-selector"
        val client = "team1"
        val oAuthPrincipal = oAuthClientPrincipal(selector, client)
        val expectedRbacBuilder = getRBACFilterWithShadowRules(
            expectedPoliciesForOAuth(
                oAuthPrincipal,
                "ClientWithSelector(name=$client, selector=$selector)",
                "OAuth(provider=oauth-provider, verification=OFFLINE, policy=ALLOW_MISSING_OR_FAILED)"
            ),
            expectedPoliciesForOAuth(
                oAuthPrincipal,
                "ClientWithSelector(name=$client, selector=$selector)",
                "OAuth(provider=oauth-provider, verification=OFFLINE, policy=ALLOW_MISSING_OR_FAILED)"
            )
        )
        val incomingPermission = Incoming(
            permissionsEnabled = true,
            endpoints = listOf(
                IncomingEndpoint(
                    "/oauth-protected",
                    PathMatchingType.PATH,
                    setOf("GET"),
                    setOf(ClientWithSelector(client, selector)),
                    oauth = OAuth("oauth-provider", policy = OAuth.Policy.ALLOW_MISSING_OR_FAILED)
                )
            )
        )

        // when
        val generated = rbacFilterFactoryWithOAuth.createHttpFilter(createGroup(incomingPermission), snapshot)

        // then
        Assertions.assertThat(generated).isEqualTo(expectedRbacBuilder)
    }

    private fun oAuthClientPrincipal(selector: String, client: String) = """{
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
                    oauth = OAuth(
                        provider = "oauth-provider",
                        verification = OAuth.Verification.OFFLINE,
                        policy = OAuth.Policy.ALLOW_MISSING_OR_FAILED
                    )
                )
            )
        )
        val expectedRbacBuilder = getRBACFilterWithShadowRules(
            expectedPoliciesForOAuth(
                authenticatedPrincipal("client1"),
                "ClientWithSelector(name=client1, selector=null)",
                "OAuth(provider=oauth-provider, verification=OFFLINE, policy=ALLOW_MISSING_OR_FAILED)"
            ),
            expectedPoliciesForOAuth(
                authenticatedPrincipal("client1"),
                "ClientWithSelector(name=client1, selector=null)",
                "OAuth(provider=oauth-provider, verification=OFFLINE, policy=ALLOW_MISSING_OR_FAILED)"
            )
        )

        // when
        val generated = rbacFilterFactoryWithOAuth.createHttpFilter(createGroup(incomingPermission), snapshot)

        // then
        Assertions.assertThat(generated).isEqualTo(expectedRbacBuilder)
    }

    @ParameterizedTest
    @EnumSource(OAuth.Policy::class)
    fun `should generate RBAC rules for Clients with OAuth selectors and OAuth Policies`(policy: OAuth.Policy) {
        // given
        val selector = "oauth-selector"
        val client = "team1"
        val incomingPermission = Incoming(
            permissionsEnabled = true,
            endpoints = listOf(
                IncomingEndpoint(
                    "/oauth-protected",
                    PathMatchingType.PATH,
                    setOf("GET"),
                    setOf(ClientWithSelector(client, selector)),
                    oauth = OAuth("oauth-provider", policy = policy)
                )
            )
        )
        val expectedRbacBuilder = getRBACFilterWithShadowRules(
            expectedPoliciesForOAuth(
                principalsForClientsSelectorAndPolicies(selector, client, policy),
                "ClientWithSelector(name=$client, selector=$selector)",
                "OAuth(provider=oauth-provider, verification=OFFLINE, policy=$policy)"
            ),
            expectedPoliciesForOAuth(
                principalsForClientsSelectorAndPolicies(selector, client, policy),
                "ClientWithSelector(name=$client, selector=$selector)",
                "OAuth(provider=oauth-provider, verification=OFFLINE, policy=$policy)"
            )
        )

        val generated = rbacFilterFactoryWithOAuth.createHttpFilter(createGroup(incomingPermission), snapshot)

        Assertions.assertThat(generated).isEqualTo(expectedRbacBuilder)
    }

    private fun principalsForClientsSelectorAndPolicies(selector: String, client: String, policy: OAuth.Policy) =
        if (policy == OAuth.Policy.ALLOW_MISSING_OR_FAILED) {
            oAuthClientPrincipal(selector, client)
        } else {
            oAuthClientPrincipal(selector, client) + "," + principalForOAuthPolicy(policy)
        }

    private fun expectedPoliciesForOAuth(principals: String, clientsWithSelector: String, oauth: String) = """
        {
          "policies": {
            "IncomingEndpoint(path=/oauth-protected, pathMatchingType=PATH, methods=[GET], clients=[$clientsWithSelector], unlistedClientsPolicy=BLOCKANDLOG, oauth=$oauth)": {
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

    private fun principalForOAuthPolicy(policy: OAuth.Policy): String = when (policy) {
        OAuth.Policy.STRICT -> """{
              "andIds": {
                "ids": [{
                  "metadata": {
                    "filter": "envoy.filters.http.header_to_metadata",
                    "path": [{
                      "key": "jwt-missing"
                    }],
                    "value": {
                      "boolMatch": false
                    }
                  }
                }, {
                  "metadata": {
                    "filter": "envoy.filters.http.jwt_authn",
                    "path": [{
                      "key": "jwt"
                    }],
                    "value": {
                      "presentMatch": true
                    }
                  }
                }]
              }
            }"""
        OAuth.Policy.ALLOW_MISSING -> """{
      "orIds": {
        "ids": [{
          "metadata": {
            "filter": "envoy.filters.http.header_to_metadata",
            "path": [{
              "key": "jwt-missing"
            }],
            "value": {
              "boolMatch": true
            }
          }
        }, {
          "andIds": {
            "ids": [{
              "metadata": {
                "filter": "envoy.filters.http.header_to_metadata",
                "path": [{
                  "key": "jwt-missing"
                }],
                "value": {
                  "boolMatch": false
                }
              }
            }, {
              "metadata": {
                "filter": "envoy.filters.http.jwt_authn",
                "path": [{
                  "key": "jwt"
                }],
                "value": {
                  "presentMatch": true
                }
              }
            }]
          }
        }]
      }
    }"""
        OAuth.Policy.ALLOW_MISSING_OR_FAILED -> """{
                    "not_id": {
                      "any": true
                    }
                }"""
    }
}
