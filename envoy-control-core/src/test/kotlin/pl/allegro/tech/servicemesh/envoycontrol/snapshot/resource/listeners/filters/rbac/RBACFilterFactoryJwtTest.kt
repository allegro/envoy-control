package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters.rbac

import io.envoyproxy.controlplane.cache.SnapshotResources
import io.envoyproxy.envoy.config.cluster.v3.Cluster
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment
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

    private val jwtProperties = JwtFilterProperties().also {
        it.providers =
            mapOf(
                "oauth-provider" to OAuthProvider(
                    matchings = mapOf("oauth-prefix" to "authorities")
                )
            )
        it.fieldRequiredInToken = "exp"
    }

    private val rbacFilterFactoryWithOAuth = RBACFilterFactory(
        IncomingPermissionsProperties().also {
            it.enabled = true
            it.overlappingPathsFix = true
        },
        StatusRouteProperties(),
        jwtProperties = jwtProperties
    )

    val snapshot = GlobalSnapshot(
        SnapshotResources.create<Cluster>(listOf<Cluster>(), "").resources(),
        setOf(),
        SnapshotResources.create<ClusterLoadAssignment>(listOf<ClusterLoadAssignment>(), "").resources(),
        mapOf(),
        SnapshotResources.create<Cluster>(listOf<Cluster>(), "").resources()
    )

    @ParameterizedTest(name = "should generate RBAC rules for {arguments} OAuth Policy")
    @EnumSource(OAuth.Policy::class)
    fun `should generate RBAC rules for OAuth Policy`(policy: OAuth.Policy) {
        // given
        val client = "client1"
        val oAuthPolicyPrincipal = principalForOAuthPolicy(policy, client)
        val expectedRbacBuilder = getRBACFilterWithShadowRules(
            expectedPoliciesForOAuth(
                oAuthPolicyPrincipal,
                "ClientWithSelector(name=$client, selector=null)",
                "OAuth(provider=oauth-provider, verification=OFFLINE, policy=$policy)"
            ),
            expectedPoliciesForOAuth(
                oAuthPolicyPrincipal,
                "ClientWithSelector(name=$client, selector=null)",
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
                    setOf(ClientWithSelector(client)),
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
        val selector = "team1"
        val client = "oauth-prefix"
        val oAuthPrincipal = oAuthClientPrincipal(getTokenFieldForClientWithSelector("oauth-provider", client), selector)
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

    @ParameterizedTest(name = "should generate RBAC rules for {arguments} if no clients and unlisted clients policy is log")
    @EnumSource(OAuth.Policy::class)
    fun `should generate RBAC rules for OAuth Policy if no clients and unlisted clients policy is log`(policy: OAuth.Policy) {
        // given
        val unlistedClientsPolicy = Incoming.UnlistedPolicy.LOG
        val incomingPermission = Incoming(
            permissionsEnabled = true,
            endpoints = listOf(
                IncomingEndpoint(
                    "/oauth-protected",
                    PathMatchingType.PATH,
                    setOf("GET"),
                    setOf(),
                    unlistedClientsPolicy,
                    oauth = OAuth(
                        provider = "oauth-provider",
                        verification = OAuth.Verification.OFFLINE,
                        policy = policy
                    )
                )
            )
        )

        val expectedRbacBuilder = getRBACFilterWithShadowRules(
            expectedPoliciesForOAuth(
                oAuthPrincipalsNoClients(policy),
                "",
                "OAuth(provider=oauth-provider, verification=OFFLINE, policy=$policy)",
                "$unlistedClientsPolicy"
            ),
            expectedPoliciesForOAuth(
                oAuthPrincipalsNoClients(policy),
                "",
                "OAuth(provider=oauth-provider, verification=OFFLINE, policy=$policy)",
                "$unlistedClientsPolicy"
            )
        )

        // when
        val generated = rbacFilterFactoryWithOAuth.createHttpFilter(createGroup(incomingPermission), snapshot)

        // then
        Assertions.assertThat(generated).isEqualTo(expectedRbacBuilder)
    }

    @ParameterizedTest(name = "should generate RBAC rules for {arguments} if no clients and unlistedClientsPolicy is blockAndLog")
    @EnumSource(OAuth.Policy::class)
    fun `should generate RBAC rules for OAuth Policy if no clients and unlisted clients policy is blockAndLog`(policy: OAuth.Policy) {
        // given
        val unlistedClientsPolicy = Incoming.UnlistedPolicy.BLOCKANDLOG
        val incomingPermission = Incoming(
            permissionsEnabled = true,
            endpoints = listOf(
                IncomingEndpoint(
                    "/oauth-protected",
                    PathMatchingType.PATH,
                    setOf("GET"),
                    setOf(),
                    unlistedClientsPolicy,
                    oauth = OAuth(
                        provider = "oauth-provider",
                        verification = OAuth.Verification.OFFLINE,
                        policy = policy
                    )
                )
            )
        )

        val expectedRbacBuilder = getRBACFilterWithShadowRules(
            expectedPoliciesForOAuth(
                """{ "not_id":  {"any": "true"} }""",
                "",
                "OAuth(provider=oauth-provider, verification=OFFLINE, policy=$policy)",
                "$unlistedClientsPolicy"
            ),
            expectedPoliciesForOAuth(
                """{ "not_id":  {"any": "true"} }""",
                "",
                "OAuth(provider=oauth-provider, verification=OFFLINE, policy=$policy)",
                "$unlistedClientsPolicy"
            )
        )

        // when
        val generated = rbacFilterFactoryWithOAuth.createHttpFilter(createGroup(incomingPermission), snapshot)

        // then
        Assertions.assertThat(generated).isEqualTo(expectedRbacBuilder)
    }

    private fun getTokenFieldForClientWithSelector(provider: String, client: String) =
        jwtProperties.providers[provider]!!.matchings[client]!!

    private fun oAuthClientPrincipal(selectorMatching: String, selector: String) = """{
                       "metadata": {
                        "filter": "envoy.filters.http.jwt_authn",
                        "path": [
                         {
                          "key": "jwt"
                         },
                         {
                          "key": "$selectorMatching"
                         }
                        ],
                        "value": {
                         "list_match": {
                          "one_of": {
                           "string_match": {
                            "exact": "$selector"
                           }
                          }
                         }
                        }
                       }
                      }"""

    private fun expectedPoliciesForOAuth(
        principals: String,
        clientsWithSelector: String,
        oauth: String,
        unlistedClientsPolicy: String = "BLOCKANDLOG"
    ) = """
        {
          "policies": {
            "IncomingEndpoint(path=/oauth-protected, pathMatchingType=PATH, methods=[GET], clients=[$clientsWithSelector], unlistedClientsPolicy=$unlistedClientsPolicy, oauth=$oauth)": {
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

    private fun principalForOAuthPolicy(policy: OAuth.Policy, client: String): String = when (policy) {
        OAuth.Policy.STRICT -> """{
          "andIds": {
            "ids": [{
              "andIds": {
                "ids": [{
                  "metadata": {
                    "filter": "envoy.filters.http.header_to_metadata",
                    "path": [{
                      "key": "jwt-status"
                    }],
                    "value": {
                      "stringMatch": {
                        "exact": "present"
                      }
                    }
                  }
                }, {
                  "metadata": {
                    "filter": "envoy.filters.http.jwt_authn",
                    "path": [{
                      "key": "jwt"
                    }, {
                      "key": "exp"
                    }],
                    "value": {
                      "presentMatch": true
                    }
                  }
                }]
              }
            }, {
              "authenticated": {
                "principalName": {
                  "exact": "spiffe://$client"
                }
              }
            }]
          }
        }"""
        OAuth.Policy.ALLOW_MISSING -> """{
          "andIds": {
            "ids": [{
              "orIds": {
                "ids": [{
                  "metadata": {
                    "filter": "envoy.filters.http.header_to_metadata",
                    "path": [{
                      "key": "jwt-status"
                    }],
                    "value": {
                      "stringMatch": {
                        "exact": "missing"
                      }
                    }
                  }
                }, {
                  "andIds": {
                    "ids": [{
                      "metadata": {
                        "filter": "envoy.filters.http.header_to_metadata",
                        "path": [{
                          "key": "jwt-status"
                        }],
                        "value": {
                          "stringMatch": {
                            "exact": "present"
                          }
                        }
                      }
                    }, {
                      "metadata": {
                        "filter": "envoy.filters.http.jwt_authn",
                        "path": [{
                          "key": "jwt"
                        }, {
                          "key": "exp"
                        }],
                        "value": {
                          "presentMatch": true
                        }
                      }
                    }]
                  }
                }]
              }
            }, {
              "authenticated": {
                "principalName": {
                  "exact": "spiffe://$client"
                }
              }
            }]
          }
        }"""
        OAuth.Policy.ALLOW_MISSING_OR_FAILED -> authenticatedPrincipal("client1")
    }

    private fun oAuthPrincipalsNoClients(policy: OAuth.Policy): String {
        return when (policy) {
            OAuth.Policy.STRICT -> """{
              "andIds": {
                "ids": [{
                  "metadata": {
                    "filter": "envoy.filters.http.header_to_metadata",
                    "path": [{
                      "key": "jwt-status"
                    }],
                    "value": {
                      "stringMatch": {
                        "exact": "present"
                      }
                    }
                  }
                }, {
                  "metadata": {
                    "filter": "envoy.filters.http.jwt_authn",
                    "path": [{
                       "key": "jwt"
                      }, {
                      "key": "exp"
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
                      "key": "jwt-status"
                    }],
                    "value": {
                      "stringMatch": {
                        "exact": "missing"
                      }
                    }
                  }
                }, {
                  "andIds": {
                    "ids": [{
                      "metadata": {
                        "filter": "envoy.filters.http.header_to_metadata",
                        "path": [{
                          "key": "jwt-status"
                        }],
                        "value": {
                          "stringMatch": {
                            "exact": "present"
                          }
                        }
                      }
                    }, {
                      "metadata": {
                        "filter": "envoy.filters.http.jwt_authn",
                            "path": [{
                              "key": "jwt"
                            }, {
                              "key": "exp"
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
            OAuth.Policy.ALLOW_MISSING_OR_FAILED -> """{"any": true}"""
        }
    }
}
