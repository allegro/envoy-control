package pl.allegro.tech.servicemesh.envoycontrol.snapshot.listeners.filters

import com.google.protobuf.Any
import com.google.protobuf.util.JsonFormat
import io.envoyproxy.controlplane.cache.SnapshotResources
import io.envoyproxy.envoy.api.v2.ClusterLoadAssignment
import io.envoyproxy.envoy.api.v2.core.Address
import io.envoyproxy.envoy.api.v2.core.SocketAddress
import io.envoyproxy.envoy.api.v2.endpoint.Endpoint
import io.envoyproxy.envoy.api.v2.endpoint.LbEndpoint
import io.envoyproxy.envoy.api.v2.endpoint.LocalityLbEndpoints
import io.envoyproxy.envoy.config.filter.network.http_connection_manager.v2.HttpFilter
import io.envoyproxy.envoy.config.filter.http.rbac.v2.RBAC as RBACFilter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.groups.ClientWithSelector
import pl.allegro.tech.servicemesh.envoycontrol.groups.CommunicationMode
import pl.allegro.tech.servicemesh.envoycontrol.groups.Incoming
import pl.allegro.tech.servicemesh.envoycontrol.groups.IncomingEndpoint
import pl.allegro.tech.servicemesh.envoycontrol.groups.PathMatchingType
import pl.allegro.tech.servicemesh.envoycontrol.groups.ProxySettings
import pl.allegro.tech.servicemesh.envoycontrol.groups.Role
import pl.allegro.tech.servicemesh.envoycontrol.groups.ServicesGroup
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.GlobalSnapshot
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.IncomingPermissionsProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SelectorMatching
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SourceIpAuthenticationProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.StatusRouteProperties

@Suppress("LargeClass") // TODO: https://github.com/allegro/envoy-control/issues/121
internal class RBACFilterFactoryTest {
    private val rRBACFilterPermissions = RBACFilterPermissions()

    private val rbacFilterFactory = RBACFilterFactory(
            IncomingPermissionsProperties().also { it.enabled = true },
            StatusRouteProperties(),
            rRBACFilterPermissions
    )
    private val rbacFilterFactoryWithSourceIpAuth = RBACFilterFactory(
            IncomingPermissionsProperties().also {
                it.enabled = true
                it.sourceIpAuthentication = SourceIpAuthenticationProperties().also { ipProperties ->
                    ipProperties.ipFromServiceDiscovery.enabledForIncomingServices = listOf("client1")
                }
            },
            StatusRouteProperties(),
            rRBACFilterPermissions
    )
    private val rbacFilterFactoryWithStaticRange = RBACFilterFactory(
            IncomingPermissionsProperties().also {
                it.enabled = true
                it.sourceIpAuthentication = SourceIpAuthenticationProperties().also { ipProperties ->
                    ipProperties.ipFromRange = mutableMapOf(
                        "client1" to setOf("192.168.1.0/24", "192.168.2.0/28")
                    )
                }
            },
            StatusRouteProperties(),
            rRBACFilterPermissions
    )
    private val rbacFilterFactoryWithStaticRangeAndSourceIpAuth = RBACFilterFactory(
            IncomingPermissionsProperties().also {
                it.enabled = true
                it.sourceIpAuthentication = SourceIpAuthenticationProperties().also { ipProperties ->
                    ipProperties.ipFromServiceDiscovery.enabledForIncomingServices = listOf("client1")
                    ipProperties.ipFromRange = mutableMapOf("client2" to setOf("192.168.1.0/24", "192.168.2.0/28"))
                }
            },
            StatusRouteProperties(),
            rRBACFilterPermissions
    )
    private val rbacFilterFactoryWithSourceIpWithSelectorAuth = RBACFilterFactory(
            IncomingPermissionsProperties().also {
                it.enabled = true
                it.sourceIpAuthentication = SourceIpAuthenticationProperties().also { ipProperties ->
                    ipProperties.ipFromServiceDiscovery.enabledForIncomingServices = listOf("client1")
                    ipProperties.ipFromRange = mutableMapOf(
                        "client2" to setOf("192.168.1.0/24", "192.168.2.0/28")
                    )
                }
                it.selectorMatching = mutableMapOf(
                        "client1" to SelectorMatching().also { it.header = "x-secret-header" },
                        "client2" to SelectorMatching().also { it.header = "x-secret-header" }
                )
            },
            StatusRouteProperties(),
            rRBACFilterPermissions
    )

    val snapshot = GlobalSnapshot(
            SnapshotResources.create(listOf(), ""),
            mapOf(),
            SnapshotResources.create(listOf(), ""),
            mapOf(),
            SnapshotResources.create(listOf(), "")
    )

    val clusterLoadAssignment = ClusterLoadAssignment.newBuilder()
            .setClusterName("client1")
            .addEndpoints(LocalityLbEndpoints.newBuilder()
                    .addLbEndpoints(LbEndpoint.newBuilder()
                            .setEndpoint(Endpoint.newBuilder()
                                    .setAddress(Address.newBuilder()
                                            .setSocketAddress(SocketAddress.newBuilder()
                                                    .setAddress("127.0.0.1")
                                            )
                                    )
                            )
                    )
            ).build()

    val snapshotForSourceIpAuth = GlobalSnapshot(
            SnapshotResources.create(listOf(), ""),
            mapOf(),
            SnapshotResources.create(listOf(clusterLoadAssignment), ""),
            mapOf(),
            SnapshotResources.create(listOf(), "")
    )

    @Test
    fun `should create RBAC filter with status route permissions when no incoming permissions are defined`() {
        // given
        val rbacFilterFactoryWithStatusRoute = RBACFilterFactory(
                IncomingPermissionsProperties().also { it.enabled = true },
                StatusRouteProperties().also { it.enabled = true },
                rRBACFilterPermissions
        )
        val incomingPermission = Incoming(permissionsEnabled = true)
        val expectedRbacBuilder = getRBACFilter(expectedStatusRoutePermissionsJson)

        // when
        val generated = rbacFilterFactoryWithStatusRoute.createHttpFilter(createGroup(incomingPermission), snapshot)

        // then
        assertThat(generated).isEqualTo(expectedRbacBuilder)
    }

    @Test
    fun `should create shadow RBAC filter with status route permissions when no incoming permissions are defined`() {
        // given
        val rbacFilterFactoryWithStatusRoute = RBACFilterFactory(
                IncomingPermissionsProperties().also { it.enabled = true },
                StatusRouteProperties().also { it.enabled = true },
                rRBACFilterPermissions
        )
        val incomingPermission = Incoming(permissionsEnabled = true)
        val expectedRbacBuilder = getRBACFilter(expectedStatusRoutePermissionsJson)

        // when
        val generated = rbacFilterFactoryWithStatusRoute.createHttpFilter(createGroup(incomingPermission), snapshot)

        // then
        assertThat(generated).isEqualTo(expectedRbacBuilder)
    }

    @Test
    fun `should not create RBAC filter when no incoming permissions are defined`() {
        // given
        val incomingPermission = null

        // when
        val generated = rbacFilterFactory.createHttpFilter(createGroup(incomingPermission), snapshot)

        // then
        assertThat(generated).isEqualTo(null)
    }

    @Test
    fun `should not create RBAC filter when incoming permissions are disabled`() {
        // given
        val incomingPermission = Incoming(permissionsEnabled = false)

        // when
        val generated = rbacFilterFactory.createHttpFilter(createGroup(incomingPermission), snapshot)

        // then
        assertThat(generated).isEqualTo(null)
    }

    @Test
    fun `should create RBAC filter when incoming permissions are enabled`() {
        // given
        val incomingPermission = Incoming(permissionsEnabled = true)

        // when
        val generated = rbacFilterFactory.createHttpFilter(createGroup(incomingPermission), snapshot)

        // then
        assertThat(generated).isNotEqualTo(null)
    }

    @Test
    fun `should generate RBAC rules for incoming permissions with no endpoints allowed`() {
        // given
        val expectedRbacBuilder = getRBACFilter(expectedEmptyEndpointPermissions)
        val incomingPermission = Incoming(
                permissionsEnabled = true,
                endpoints = listOf()
        )

        // when
        val generated = rbacFilterFactory.createHttpFilter(createGroup(incomingPermission), snapshot)

        // then
        assertThat(generated).isEqualTo(expectedRbacBuilder)
    }

    @Test
    fun `should generate RBAC rules for incoming permissions with log unlisted clients and endpoints`() {
        // given

        val expectedShadowRules = """
        {
          "policies": {
            "client1": {
              "permissions": [
                {
                  "and_rules": {
                    "rules": [
                      ${pathRule("/example")},
                      {
                        "or_rules": {
                          "rules": [
                            ${methodRule("GET")},
                            ${methodRule("POST")}
                          ]
                        }
                      }
                    ]
                  }
                }
              ], "principals": [
                ${principalHeader("x-service-name", "client1")}
              ]
            }
          }
        }
        """

        val expectedRbacBuilder = getRBACFilterWithShadowRules(
            expectedAnyPermissionJson,
            expectedShadowRules
        )
        val incomingPermission = Incoming(
            permissionsEnabled = true,
            endpoints = listOf(
                IncomingEndpoint(
                "/example",
                    PathMatchingType.PATH,
                    setOf("GET", "POST"),
                    setOf(ClientWithSelector("client1")),
                    IncomingEndpoint.UnlistedClientsPolicy.LOG
                )
            ),
            unlistedEndpointsPolicy = Incoming.UnlistedEndpointsPolicy.LOG
        )

        // when
        val generated = rbacFilterFactory.createHttpFilter(createGroup(incomingPermission), snapshot)

        // then
        assertThat(generated).isEqualTo(expectedRbacBuilder)
    }

    @Test
    fun `should generate RBAC rules for incoming permissions with log unlisted endpoints and block clients`() {
        // given

        val expectedShadowRules = """
        {
          "policies": {
            "client1": {
              "permissions": [
                {
                  "and_rules": {
                    "rules": [
                      ${pathRule("/example")},
                      {
                        "or_rules": {
                          "rules": [
                            ${methodRule("GET")},
                            ${methodRule("POST")}
                          ]
                        }
                      }
                    ]
                  }
                }
              ], "principals": [
                ${principalHeader("x-service-name", "client1")}
              ]
            }
          }
        }
        """

        val expectedRbacBuilder = getRBACFilterWithShadowRules(
                expectedEmptyEndpointPermissions,
                expectedShadowRules
        )
        val incomingPermission = Incoming(
                permissionsEnabled = true,
                endpoints = listOf(
                        IncomingEndpoint(
                                "/example",
                                PathMatchingType.PATH,
                                setOf("GET", "POST"),
                                setOf(ClientWithSelector("client1")),
                                IncomingEndpoint.UnlistedClientsPolicy.LOG
                        )
                ),
                unlistedEndpointsPolicy = Incoming.UnlistedEndpointsPolicy.BLOCK
        )

        // when
        val generated = rbacFilterFactory.createHttpFilter(createGroup(incomingPermission), snapshot)

        // then
        assertThat(generated).isEqualTo(expectedRbacBuilder)
    }

    @Test
    fun `should generate RBAC rules for incoming permissions with block unlisted endpoints and log clients`() {
        // given

        val expectedShadowRules = """
        {
          "policies": {
            "client1": {
              "permissions": [
                {
                  "and_rules": {
                    "rules": [
                      ${pathRule("/example")},
                      {
                        "or_rules": {
                          "rules": [
                            ${methodRule("GET")},
                            ${methodRule("POST")}
                          ]
                        }
                      }
                    ]
                  }
                }
              ], "principals": [
                ${principalHeader("x-service-name", "client1")}
              ]
            }
          }
        }
        """

        val expectedRbacBuilder = getRBACFilterWithShadowRules(
                expectedEndpointPermissionsLogUnlistedEndpointsAndBlockUnlistedClients,
                expectedShadowRules
        )
        val incomingPermission = Incoming(
                permissionsEnabled = true,
                endpoints = listOf(
                        IncomingEndpoint(
                                "/example",
                                PathMatchingType.PATH,
                                setOf("GET", "POST"),
                                setOf(ClientWithSelector("client1")),
                                IncomingEndpoint.UnlistedClientsPolicy.BLOCK
                        )
                ),
                unlistedEndpointsPolicy = Incoming.UnlistedEndpointsPolicy.LOG
        )

        // when
        val generated = rbacFilterFactory.createHttpFilter(createGroup(incomingPermission), snapshot)

        // then
        assertThat(generated).isEqualTo(expectedRbacBuilder)
    }

    @Test
    fun `should generate RBAC rules for incoming permissions with roles`() {
        // given
        val expectedRbacBuilder = getRBACFilter(expectedSimpleEndpointPermissionsJson)
        val incomingPermission = Incoming(
                permissionsEnabled = true,
                endpoints = listOf(
                        IncomingEndpoint(
                            "/example",
                            PathMatchingType.PATH,
                            setOf("GET", "POST"),
                            setOf(ClientWithSelector("role-1"))
                        )
                ),
                roles = listOf(
                            Role(
                                "role-1",
                                setOf(
                                    ClientWithSelector("client1"),
                                    ClientWithSelector("client2"))
                        )
                )
        )

        // when
        val generated = rbacFilterFactory.createHttpFilter(createGroup(incomingPermission), snapshot)

        // then
        assertThat(generated).isEqualTo(expectedRbacBuilder)
    }

    @Test
    fun `should generate RBAC rules for incoming permissions with shadow rules`() {
        // given
        val expectedRbacBuilder = getRBACFilter(expectedSimpleEndpointPermissionsJson)
        val incomingPermission = Incoming(
                permissionsEnabled = true,
                endpoints = listOf(IncomingEndpoint(
                        "/example",
                        PathMatchingType.PATH,
                        setOf("GET", "POST"),
                        setOf(ClientWithSelector("role-1"))
                )
                ),
                roles = listOf(Role("role-1", setOf(ClientWithSelector("client1"), ClientWithSelector("client2"))))
        )

        // when
        val generated = rbacFilterFactory.createHttpFilter(createGroup(incomingPermission), snapshot)

        // then
        assertThat(generated).isEqualTo(expectedRbacBuilder)
    }

    @Test
    fun `should generate RBAC rules for incoming permissions with duplicated clients`() {
        // given
        val expectedRbacBuilder = getRBACFilter(expectedDuplicatedRole)
        val incomingPermission = Incoming(
                permissionsEnabled = true,
                endpoints = listOf(IncomingEndpoint(
                        "/example",
                        PathMatchingType.PATH,
                        setOf("GET", "POST"),
                        setOf(
                                ClientWithSelector("client1"),
                                ClientWithSelector("client1"),
                                ClientWithSelector("client1", "selector"),
                                ClientWithSelector("client1-duplicated", "selector"),
                                ClientWithSelector("client1-duplicated"),
                                ClientWithSelector("role-1")
                        )
                )), roles = listOf(Role("role-1", setOf(
                        ClientWithSelector("client1-duplicated"),
                        ClientWithSelector("client1-duplicated"),
                        ClientWithSelector("client2"))
                ))
        )

        // when
        val generated = rbacFilterFactory.createHttpFilter(createGroup(incomingPermission), snapshot)

        // then
        assertThat(generated).isEqualTo(expectedRbacBuilder)
    }

    @Test
    fun `should generate RBAC with different rules for incoming permissions`() {
        // given
        val expectedRbacBuilder = getRBACFilter(expectedEndpointPermissionsWithDifferentRulesForDifferentClientsJson)
        val incomingPermission = Incoming(
                permissionsEnabled = true,
                endpoints = listOf(IncomingEndpoint(
                        "/example",
                        PathMatchingType.PATH,
                        setOf("GET"),
                        setOf(ClientWithSelector("client1"))
                ), IncomingEndpoint(
                        "/example2",
                        PathMatchingType.PATH,
                        setOf("POST"),
                        setOf(ClientWithSelector("client2"))
                ))
        )

        // when
        val generated = rbacFilterFactory.createHttpFilter(createGroup(incomingPermission), snapshot)

        // then
        assertThat(generated).isEqualTo(expectedRbacBuilder)
    }

    @Test
    fun `should generate minimal RBAC rules for incoming permissions with roles and clients`() {
        // given
        val expectedRbacBuilder = getRBACFilter(expectedTwoClientsSimpleEndpointPermissionsJson)
        val incomingPermission = Incoming(
                permissionsEnabled = true,
                endpoints = listOf(
                    IncomingEndpoint(
                        "/example",
                        PathMatchingType.PATH,
                        setOf("GET", "POST"),
                        setOf(ClientWithSelector("role-1"))
                    ), IncomingEndpoint(
                        "/example2",
                        PathMatchingType.PATH,
                        setOf("GET", "POST"),
                        setOf(ClientWithSelector("client2"), ClientWithSelector("client1"))
                    )
                ),
                roles = listOf(Role("role-1", setOf(ClientWithSelector("client1"), ClientWithSelector("client2"))))
        )

        // when
        val generated = rbacFilterFactory.createHttpFilter(createGroup(incomingPermission), snapshot)

        // then
        assertThat(generated).isEqualTo(expectedRbacBuilder)
    }

    @Test
    fun `should generate minimal RBAC rules for incoming permissions with roles and single client`() {
        // given
        val expectedRbacBuilder = getRBACFilter(expectedTwoClientsSimpleEndpointPermissionsJson)
        val incomingPermission = Incoming(
                permissionsEnabled = true,
                endpoints = listOf(IncomingEndpoint(
                        "/example",
                        PathMatchingType.PATH,
                        setOf("GET", "POST"),
                        setOf(ClientWithSelector("client2"), ClientWithSelector("role-1"))
                ), IncomingEndpoint(
                        "/example2",
                        PathMatchingType.PATH,
                        setOf("GET", "POST"),
                        setOf(ClientWithSelector("role-2"), ClientWithSelector("client1"))
                )), roles = listOf(Role("role-1", setOf(ClientWithSelector("client1"))), Role("role-2", setOf(ClientWithSelector("client2"))))
        )

        // when
        val generated = rbacFilterFactory.createHttpFilter(createGroup(incomingPermission), snapshot)

        // then
        assertThat(generated).isEqualTo(expectedRbacBuilder)
    }

    @Test
    fun `should generate RBAC rules for incoming permissions with two endpoints containing methods and clients`() {
        // given
        val expectedRbacBuilder = getRBACFilter(expectedTwoClientsSimpleEndpointPermissionsJson)
        val incomingPermission = Incoming(
                permissionsEnabled = true,
                endpoints = listOf(IncomingEndpoint(
                        "/example",
                        PathMatchingType.PATH,
                        setOf("GET", "POST"),
                        setOf(ClientWithSelector("client1"), ClientWithSelector("client2"))
                ), IncomingEndpoint(
                        "/example2",
                        PathMatchingType.PATH,
                        setOf("GET", "POST"),
                        setOf(ClientWithSelector("client1"), ClientWithSelector("client2"))
                ))
        )

        // when
        val generated = rbacFilterFactory.createHttpFilter(createGroup(incomingPermission), snapshot)

        // then
        assertThat(generated).isEqualTo(expectedRbacBuilder)
    }

    @Test
    fun `should generate RBAC rules for incoming permissions with methods and clients`() {
        // given
        val expectedRbacBuilder = getRBACFilter(expectedSimpleEndpointPermissionsJson)
        val incomingPermission = Incoming(
                permissionsEnabled = true,
                endpoints = listOf(IncomingEndpoint(
                        "/example",
                        PathMatchingType.PATH,
                        setOf("GET", "POST"),
                        setOf(ClientWithSelector("client1"), ClientWithSelector("client2"))
                ))
        )

        // when
        val generated = rbacFilterFactory.createHttpFilter(createGroup(incomingPermission), snapshot)

        // then
        assertThat(generated).isEqualTo(expectedRbacBuilder)
    }

    @Test
    fun `should generate RBAC rules for incoming permissions with source ip authentication`() {
        // given
        val expectedRbacBuilder = getRBACFilter(expectedSourceIpAuthPermissionsJson)
        val incomingPermission = Incoming(
                permissionsEnabled = true,
                endpoints = listOf(IncomingEndpoint(
                        "/example",
                        PathMatchingType.PATH,
                        setOf("GET", "POST"),
                        setOf(ClientWithSelector("client1"), ClientWithSelector("client2"))
                ))
        )

        // when
        val generated = rbacFilterFactoryWithSourceIpAuth.createHttpFilter(createGroup(incomingPermission), snapshotForSourceIpAuth)

        // then
        assertThat(generated).isEqualTo(expectedRbacBuilder)
    }

    @Test
    fun `should generate RBAC rules for incoming permissions without methods defined`() {
        // given
        val expectedRbacBuilder = getRBACFilter(expectedEndpointPermissionsWithoutMethodsJson)
        val incomingPermission = Incoming(
                permissionsEnabled = true,
                endpoints = listOf(IncomingEndpoint(
                        "/example",
                        PathMatchingType.PATH,
                        setOf(),
                        setOf(ClientWithSelector("client1"), ClientWithSelector("client2"))
                ))
        )

        // when
        val generated = rbacFilterFactory.createHttpFilter(createGroup(incomingPermission), snapshot)

        // then
        assertThat(generated).isEqualTo(expectedRbacBuilder)
    }

    @Test
    fun `should generate RBAC rules for incoming permissions without clients`() {
        // given
        val expectedRbacBuilder = getRBACFilter(expectedEmptyEndpointPermissions)
        val incomingPermission = Incoming(
                permissionsEnabled = true,
                endpoints = listOf(IncomingEndpoint(
                        "/example",
                        PathMatchingType.PATH,
                        setOf("GET", "POST"),
                        setOf()
                ))
        )

        // when
        val generated = rbacFilterFactory.createHttpFilter(createGroup(incomingPermission), snapshot)

        // then
        assertThat(generated).isEqualTo(expectedRbacBuilder)
    }

    @Test
    fun `should generate RBAC rules for incoming permissions with static ip range authentication`() {
        // given
        val expectedRbacBuilder = getRBACFilter(expectedSourceIpAuthWithStaticRangeJson)
        val incomingPermission = Incoming(
                permissionsEnabled = true,
                endpoints = listOf(IncomingEndpoint(
                        "/example",
                        PathMatchingType.PATH,
                        setOf("GET"),
                        setOf(ClientWithSelector("client1"))
                ))
        )

        // when
        val generated = rbacFilterFactoryWithStaticRange.createHttpFilter(createGroup(incomingPermission), snapshot)

        // then
        assertThat(generated).isEqualTo(expectedRbacBuilder)
    }

    @Test
    fun `should generate RBAC rules for incoming permissions with static ip range authentication and client source ip`() {
        // given
        val expectedRbacBuilder = getRBACFilter(expectedSourceIpAuthWithStaticRangeAndSourceIpJson)
        val incomingPermission = Incoming(
                permissionsEnabled = true,
                endpoints = listOf(IncomingEndpoint(
                        "/example",
                        PathMatchingType.PATH,
                        setOf("GET"),
                        setOf(ClientWithSelector("client1"), ClientWithSelector("client2"))
                ))
        )

        // when
        val generated = rbacFilterFactoryWithStaticRangeAndSourceIpAuth.createHttpFilter(
                createGroup(incomingPermission),
                snapshotForSourceIpAuth
        )

        // then
        assertThat(generated).isEqualTo(expectedRbacBuilder)
    }

    @Test
    fun `should generate RBAC rules for incoming permissions with source ip and selector authentication`() {
        // given
        val expectedRbacBuilder = getRBACFilter(expectedSourceIpWithSelectorAuthPermissionsJson)
        val incomingPermission = Incoming(
                permissionsEnabled = true,
                endpoints = listOf(IncomingEndpoint(
                        "/example",
                        PathMatchingType.PATH,
                        setOf("GET"),
                        setOf(ClientWithSelector("client2", "selector"))
                ))
        )

        // when
        val generated = rbacFilterFactoryWithSourceIpWithSelectorAuth.createHttpFilter(
                createGroup(incomingPermission),
                snapshotForSourceIpAuth
        )

        // then
        assertThat(generated).isEqualTo(expectedRbacBuilder)
    }

    @Test
    fun `should generate RBAC rules for incoming permissions with source ip from discovery and selector authentication`() {
        // given
        val expectedRbacBuilder = getRBACFilter(expectedSourceIpFromDiscoveryWithSelectorAuthPermissionsJson)
        val incomingPermission = Incoming(
                permissionsEnabled = true,
                endpoints = listOf(IncomingEndpoint(
                        "/example",
                        PathMatchingType.PATH,
                        setOf("GET"),
                        setOf(ClientWithSelector("client1", "selector"))
                ))
        )

        // when
        val generated = rbacFilterFactoryWithSourceIpWithSelectorAuth.createHttpFilter(
                createGroup(incomingPermission),
                snapshotForSourceIpAuth
        )

        // then
        assertThat(generated).isEqualTo(expectedRbacBuilder)
    }

    @Test
    fun `should generate RBAC rules for incoming permissions with source ip and selector authentication for roles`() {
        // given
        val expectedRbacBuilder = getRBACFilter(expectedSourceIpWithStaticRangeAndSelectorAuthPermissionsAndRolesJson)
        val incomingPermission = Incoming(
                permissionsEnabled = true,
                endpoints = listOf(IncomingEndpoint(
                        "/example",
                        PathMatchingType.PATH,
                        setOf("GET"),
                        setOf(ClientWithSelector("role1"))
                )),
                roles = listOf(Role("role1", setOf(
                        ClientWithSelector("client1", "selector1"),
                        ClientWithSelector("client2", "selector2"))
                ))
        )

        // when
        val generated = rbacFilterFactoryWithSourceIpWithSelectorAuth.createHttpFilter(
                createGroup(incomingPermission),
                snapshotForSourceIpAuth
        )

        // then
        assertThat(generated).isEqualTo(expectedRbacBuilder)
    }

    private val expectedEndpointPermissionsWithDifferentRulesForDifferentClientsJson = """
        {
          "policies": {
            "client1": {
              "permissions": [
                {
                  "and_rules": {
                    "rules": [
                      ${pathRule("/example")},
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
              ], "principals": [
                ${principalHeader("x-service-name", "client1")}
              ]
            },
            "client2": {
              "permissions": [
                {
                  "and_rules": {
                    "rules": [
                      ${pathRule("/example2")},
                      {
                        "or_rules": {
                          "rules": [
                            ${methodRule("POST")}
                          ]
                        }
                      }
                    ]
                  }
                }
              ], "principals": [
                ${principalHeader("x-service-name", "client2")}
              ]
            }
          }
        }
    """

    private val expectedSourceIpFromDiscoveryWithSelectorAuthPermissionsJson = """
        {
          "policies": {
            "client1:selector": {
              "permissions": [
                {
                  "and_rules": {
                    "rules": [
                      ${pathRule("/example")},
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
              ], "principals": [
                {
                  "andIds": {
                    "ids": [{
                        "orIds": {
                            "ids": [${principalSourceIp("127.0.0.1")}]
                        }
                    }, ${principalHeader("x-secret-header", "selector")}]
                  }
                }
              ]
            }
          }
        }
    """

    private val expectedSourceIpWithSelectorAuthPermissionsJson = """
        {
          "policies": {
            "client2:selector": {
              "permissions": [
                {
                  "and_rules": {
                    "rules": [
                      ${pathRule("/example")},
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
              ], "principals": [
                {
                  "andIds": {
                    "ids": [{
                        "orIds": {
                            "ids": [${principalSourceIp("192.168.1.0", 24)}, ${principalSourceIp("192.168.2.0", 28)}]
                        }
                    }, ${principalHeader("x-secret-header", "selector")}]
                  }
                }
              ]
            }
          }
        }
    """

    private val expectedSourceIpWithStaticRangeAndSelectorAuthPermissionsAndRolesJson = """
        {
          "policies": {
            "client1:selector1,client2:selector2": {
              "permissions": [
                {
                  "and_rules": {
                    "rules": [
                      ${pathRule("/example")},
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
              ], "principals": [
                {
                  "andIds": {
                    "ids": [{
                        "orIds": {
                            "ids": [${principalSourceIp("127.0.0.1")}]
                        }
                    }, ${principalHeader("x-secret-header", "selector1")}]
                  }
                },
                {
                  "andIds": {
                    "ids": [{
                        "orIds": {
                            "ids": [${principalSourceIp("192.168.1.0", 24)}, ${principalSourceIp("192.168.2.0", 28)}]
                        }
                    }, ${principalHeader("x-secret-header", "selector2")}]
                  }
                }
              ]
            }
          }
        }
    """

    private val expectedSourceIpAuthPermissionsJson = """
        {
          "policies": {
            "client1,client2": {
              "permissions": [
                {
                  "and_rules": {
                    "rules": [
                      ${pathRule("/example")},
                      {
                        "or_rules": {
                          "rules": [
                            ${methodRule("GET")},
                            ${methodRule("POST")}
                          ]
                        }
                      }
                    ]
                  }
                }
              ], "principals": [
                { "orIds": { "ids": [${principalSourceIp("127.0.0.1")}] } },
                ${principalHeader("x-service-name", ("client2"))}
              ]
            }
          }
        }
    """

    private val expectedSimpleEndpointPermissionsJson = """
        {
          "policies": {
            "client1,client2": {
              "permissions": [
                {
                  "and_rules": {
                    "rules": [
                      ${pathRule("/example")},
                      {
                        "or_rules": {
                          "rules": [
                            ${methodRule("GET")},
                            ${methodRule("POST")}
                          ]
                        }
                      }
                    ]
                  }
                }
              ], "principals": [
                ${principalHeader("x-service-name", ("client1"))},
                ${principalHeader("x-service-name", ("client2"))}
              ]
            }
          }
        }
    """

    private val expectedDuplicatedRole = """
        {
          "policies": {
           """ /* notice that duplicated clients occurs only once here */ + """
            "client1,client1-duplicated,client1-duplicated:selector,client1:selector,client2": {
              "permissions": [
                {
                  "and_rules": {
                    "rules": [
                      ${pathRule("/example")},
                      {
                        "or_rules": {
                          "rules": [
                            ${methodRule("GET")},
                            ${methodRule("POST")}
                          ]
                        }
                      }
                    ]
                  }
                }
              ], "principals": [
                ${principalHeader("x-service-name", ("client1"))},
                ${principalHeader("x-service-name", ("client1-duplicated"))},
                ${principalHeader("x-service-name", ("client2"))}
              ]
            }
          }
        }
    """

    private val expectedTwoClientsSimpleEndpointPermissionsJson = """
        {
          "policies": {
            "client1,client2": {
              "permissions": [
                {
                  "and_rules": {
                    "rules": [
                      ${pathRule("/example")},
                      {
                        "or_rules": {
                          "rules": [
                            ${methodRule("GET")},
                            ${methodRule("POST")}
                          ]
                        }
                      }
                    ]
                  }
                }, 
                {
                  "and_rules": {
                    "rules": [
                      ${pathRule("/example2")},
                      {
                        "or_rules": {
                          "rules": [
                            ${methodRule("GET")},
                            ${methodRule("POST")}
                          ]
                        }
                      }
                    ]
                  }
                }
              ], "principals": [
                ${principalHeader("x-service-name", ("client1"))},
                ${principalHeader("x-service-name", ("client2"))}
              ]
            }
          }
        }
    """

    private val expectedEndpointPermissionsWithoutMethodsJson = """
        {
          "policies": {
            "client1,client2": {
              "permissions": [
                {
                  "and_rules": {
                    "rules": [
                      ${pathRule("/example")}
                    ]
                  }
                }
              ], "principals": [
                ${principalHeader("x-service-name", ("client1"))},
                ${principalHeader("x-service-name", ("client2"))}
              ]
            }
          }
        }
    """

    private val anyTrue = """
        {
            "any": "true"
        }
    """

    private val expectedStatusRoutePermissionsJson = """
        {
          "policies": {
            "_ANY_": {
              "permissions": [
                ${pathHeaderRule("/status/")}
              ], "principals": [
                $anyTrue
              ]
            }
          }
        }
    """

    private val expectedAnyPermissionJson = """
        {
          "policies": {
            "ALLOW_ANY": {
              "permissions": [
                $anyTrue
              ],
              "principals": [
                $anyTrue
              ]
            }
          }
        }
    """

    private val expectedEmptyEndpointPermissions = """{ "policies": {} }"""

    private val expectedSourceIpAuthWithStaticRangeJson = """
        {
          "policies": {
            "client1": {
              "permissions": [
                {
                  "and_rules": {
                    "rules": [
                      ${pathRule("/example")},
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
              ], "principals": [
                { "orIds": { "ids": [${principalSourceIp("192.168.1.0", 24)}, ${principalSourceIp("192.168.2.0", 28)}] } }
              ]
            }
          }
        }
    """
    // language=json
    private val expectedSourceIpAuthWithStaticRangeAndSourceIpJson = """
        {
          "policies": {
            "client1,client2": {
              "permissions": [
                {
                  "and_rules": {
                    "rules": [
                      ${pathRule("/example")},
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
              ], "principals": [
                { "orIds": { 
                  "ids": [
                      ${principalSourceIp("127.0.0.1")}
                   ]}
                 }, { "orIds": { 
                  "ids": [
                      ${principalSourceIp("192.168.1.0", 24)},
                      ${principalSourceIp("192.168.2.0", 28)}
                    ]
                  } 
                }
              ]
            }
          }
        }
    """

    private val expectedEndpointPermissionsLogUnlistedEndpointsAndBlockUnlistedClients = """
        {
          "policies": {
            "client1_not": {
              "permissions": [
                {
                  "not_rule": {
                    "and_rules": {
                        "rules": [
                          ${pathRule("/example")},
                          {
                            "or_rules": {
                              "rules": [
                                ${methodRule("GET")},
                                ${methodRule("POST")}
                              ]
                            }
                          }
                        ]
                      }
                  }
                }
              ], "principals": [
                $anyTrue
              ]
            },
            "client1": {
              "permissions": [
                {
                  "and_rules": {
                    "rules": [
                      ${pathRule("/example")},
                      {
                        "or_rules": {
                          "rules": [
                            ${methodRule("GET")},
                            ${methodRule("POST")}
                          ]
                        }
                      }
                    ]
                  }
                }
              ], "principals": [
                ${principalHeader("x-service-name", "client1")}
              ]
            }
          }
        }
    """

    private fun pathHeaderRule(path: String): String {
        return """
            {
                "header": {
                    "name": ":path",
                    "prefix_match": "$path"
                }
            }
        """
    }

    private fun pathRule(path: String): String {
        return """{
            "url_path": {
               "path": {
                    "exact": "$path"
               }
            }
        }"""
    }

    private fun methodRule(method: String): String {
        return """{
           "header": {
              "name": ":method",
              "exact_match": "$method"
           }
        }"""
    }

    private fun principalSourceIp(address: String, prefixLen: Int = 32): String {
        return """{
                "source_ip": {
                  "address_prefix": "$address",
                  "prefix_len": $prefixLen
                }
            }
        """
    }

    private fun principalHeader(name: String, value: String): String {
        return """{
                    "header": {
                      "name": "$name",
                      "exact_match": "$value"
                    }
                }"""
    }

    private fun wrapInFilter(json: String): String {
        return """
            {
                "rules": $json
            }
        """
    }

    private fun wrapInFilterShadow(shadowRulesJson: String): String {
        return """
            {
                "shadow_rules": $shadowRulesJson
            }
        """
    }

    private fun getRBACFilter(json: String): HttpFilter {
        return getRBACFilterWithShadowRules(json, json)
    }

    private fun getRBACFilterWithShadowRules(rules: String, shadowRules: String): HttpFilter {
        val rbacFilter = RBACFilter.newBuilder()
        JsonFormat.parser().merge(wrapInFilter(rules), rbacFilter)
        JsonFormat.parser().merge(wrapInFilterShadow(shadowRules), rbacFilter)

        HttpFilter.newBuilder().setName("envoy.filters.http.rbac")
                .setTypedConfig(Any.pack(rbacFilter.build())).build()

        return HttpFilter.newBuilder()
                .setName("envoy.filters.http.rbac")
                .setTypedConfig(Any.pack(rbacFilter.build()))
                .build()
    }

    private fun createGroup(
        incomingPermission: Incoming? = null,
        serviceName: String = "some-service"
    ): ServicesGroup {
        val group = ServicesGroup(
                communicationMode = CommunicationMode.ADS,
                serviceName = serviceName,
                proxySettings = ProxySettings(
                        incoming = incomingPermission ?: Incoming()
                )
        )
        return group
    }
}
