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
import pl.allegro.tech.servicemesh.envoycontrol.groups.CommunicationMode
import pl.allegro.tech.servicemesh.envoycontrol.groups.Incoming
import pl.allegro.tech.servicemesh.envoycontrol.groups.IncomingEndpoint
import pl.allegro.tech.servicemesh.envoycontrol.groups.PathMatchingType
import pl.allegro.tech.servicemesh.envoycontrol.groups.ProxySettings
import pl.allegro.tech.servicemesh.envoycontrol.groups.Role
import pl.allegro.tech.servicemesh.envoycontrol.groups.ServicesGroup
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.GlobalSnapshot
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.IncomingPermissionsProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SourceIpAuthenticationProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.StatusRouteProperties

internal class RBACFilterFactoryTest {
    private val rbacFilterFactory = RBACFilterFactory(
            IncomingPermissionsProperties().also { it.enabled = true },
            StatusRouteProperties()
    )
    private val rbacFilterFactoryWithSourceIpAuth = RBACFilterFactory(
            IncomingPermissionsProperties().also {
                it.enabled = true
                it.sourceIpAuthentication = SourceIpAuthenticationProperties().also { ipProperties ->
                    ipProperties.ipFromServiceDiscovery.enabledForIncomingServices = listOf("client1")
                }
            },
            StatusRouteProperties()
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
            StatusRouteProperties()
    )
    private val rbacFilterFactoryWithStaticRangeAndSourceIpAuth = RBACFilterFactory(
            IncomingPermissionsProperties().also {
                it.enabled = true
                it.sourceIpAuthentication = SourceIpAuthenticationProperties().also { ipProperties ->
                    ipProperties.ipFromServiceDiscovery.enabledForIncomingServices = listOf("client1")
                    ipProperties.ipFromRange = mutableMapOf(
                            "client2" to setOf("192.168.1.0/24", "192.168.2.0/28")
                    )
                }
            },
            StatusRouteProperties()
    )

    val snapshot = GlobalSnapshot(
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
            SnapshotResources.create(listOf(clusterLoadAssignment), "")
    )

    @Test
    fun `should create RBAC filter with status route permissions when no incoming permissions are defined`() {
        // given
        val rbacFilterFactoryWithStatusRoute = RBACFilterFactory(
                IncomingPermissionsProperties().also { it.enabled = true },
                StatusRouteProperties().also { it.enabled = true }
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
    fun `should generate RBAC rules for incoming permissions with roles`() {
        // given
        val expectedRbacBuilder = getRBACFilter(expectedSimpleEndpointPermissionsJson)
        val incomingPermission = Incoming(
                permissionsEnabled = true,
                endpoints = listOf(IncomingEndpoint(
                        "/example",
                        PathMatchingType.PATH,
                        setOf("GET", "POST"),
                        setOf("role-1")
                )), roles = listOf(Role("role-1", setOf("client1", "client2")))
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
                        setOf("client1")
                ), IncomingEndpoint(
                        "/example2",
                        PathMatchingType.PATH,
                        setOf("POST"),
                        setOf("client2")
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
                endpoints = listOf(IncomingEndpoint(
                        "/example",
                        PathMatchingType.PATH,
                        setOf("GET", "POST"),
                        setOf("role-1")
                ), IncomingEndpoint(
                        "/example2",
                        PathMatchingType.PATH,
                        setOf("GET", "POST"),
                        setOf("client2", "client1")
                )), roles = listOf(Role("role-1", setOf("client1", "client2")))
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
                        setOf("client2", "role-1")
                ), IncomingEndpoint(
                        "/example2",
                        PathMatchingType.PATH,
                        setOf("GET", "POST"),
                        setOf("role-2", "client1")
                )), roles = listOf(Role("role-1", setOf("client1")), Role("role-2", setOf("client2")))
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
                        setOf("client1", "client2")
                ), IncomingEndpoint(
                        "/example2",
                        PathMatchingType.PATH,
                        setOf("GET", "POST"),
                        setOf("client1", "client2")
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
                        setOf("client1", "client2")
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
                        setOf("client1", "client2")
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
                        setOf("client1", "client2")
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
                        setOf("client1")
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
                        setOf("client1", "client2")
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
                ${principalSourceIp("127.0.0.1")},
                ${principalHeader("x-service-name", "client2")}
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
                ${principalHeader("x-service-name", "client1")},
                ${principalHeader("x-service-name", "client2")}
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
                ${principalHeader("x-service-name", "client1")},
                ${principalHeader("x-service-name", "client2")}
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
                ${principalHeader("x-service-name", "client1")},
                ${principalHeader("x-service-name", "client2")}
              ]
            }
          }
        }
    """

    private val anyPrincipal = """
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
                $anyPrincipal
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
                ${principalSourceIp("192.168.1.0", 24)},
                ${principalSourceIp("192.168.2.0", 28)}
              ]
            }
          }
        }
    """

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
                ${principalSourceIp("127.0.0.1")},
                ${principalSourceIp("192.168.1.0", 24)},
                ${principalSourceIp("192.168.2.0", 28)}
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

    private fun principalHeader(header: String, principal: String): String {
        return """{
                    "header": {
                      "name": "$header",
                      "exact_match": "$principal"
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

    private fun getRBACFilter(json: String): HttpFilter {
        val rbacFilter = RBACFilter.newBuilder()
        JsonFormat.parser().merge(wrapInFilter(json), rbacFilter)
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
