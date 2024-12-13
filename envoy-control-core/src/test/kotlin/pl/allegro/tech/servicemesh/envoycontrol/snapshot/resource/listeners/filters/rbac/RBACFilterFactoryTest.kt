package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters.rbac

import io.envoyproxy.controlplane.cache.SnapshotResources
import io.envoyproxy.envoy.config.cluster.v3.Cluster
import io.envoyproxy.envoy.config.core.v3.Address
import io.envoyproxy.envoy.config.core.v3.SocketAddress
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment
import io.envoyproxy.envoy.config.endpoint.v3.Endpoint
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint
import io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.groups.ClientWithSelector
import pl.allegro.tech.servicemesh.envoycontrol.groups.Incoming
import pl.allegro.tech.servicemesh.envoycontrol.groups.IncomingEndpoint
import pl.allegro.tech.servicemesh.envoycontrol.groups.PathMatchingType
import pl.allegro.tech.servicemesh.envoycontrol.groups.Role
import pl.allegro.tech.servicemesh.envoycontrol.groups.toJson
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.ClientsListsProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.EndpointMatch
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.GlobalSnapshot
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.IncomingPermissionsProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SelectorMatching
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SourceIpAuthenticationProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.StatusRouteProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters.RBACFilterFactory

@Suppress("LargeClass") // TODO: https://github.com/allegro/envoy-control/issues/121
internal class RBACFilterFactoryTest : RBACFilterFactoryTestUtils {
    private val rbacFilterFactory = RBACFilterFactory(
            IncomingPermissionsProperties().also {
                it.enabled = true
                it.overlappingPathsFix = true
            },
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
                    ipProperties.ipFromRange = mutableMapOf("client2" to setOf("192.168.1.0/24", "192.168.2.0/28"))
                }
            },
            StatusRouteProperties()
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
        StatusRouteProperties()
    )
    private val rbacFilterFactoryWithAllowAllEndpointsForClient = RBACFilterFactory(
        IncomingPermissionsProperties().also {
            it.enabled = true
            it.clientsAllowedToAllEndpoints = mutableListOf("allowed-client")
        },
        StatusRouteProperties()
    )

    private val rbacFilterFactoryWithDefaultAndCustomClientsLists = RBACFilterFactory(
        IncomingPermissionsProperties().also {
            it.enabled = true
            it.clientsLists = ClientsListsProperties().also {
                it.defaultClientsList = listOf("default-client", "xyz")
                it.customClientsLists = mapOf(
                    "custom1" to listOf("custom1-client", "xyz"),
                    "ad:custom2" to listOf("ad:custom2-client", "xyz")
                )
            }
        },
        StatusRouteProperties()
    )

    val snapshot = GlobalSnapshot(
        SnapshotResources.create<Cluster>(listOf<Cluster>(), "").resources(),
        setOf(),
        SnapshotResources.create<ClusterLoadAssignment>(listOf<ClusterLoadAssignment>(), "").resources(),
        mapOf(),
        SnapshotResources.create<Cluster>(listOf<Cluster>(), "").resources()
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
        SnapshotResources.create<Cluster>(listOf<Cluster>(), "").resources(),
        setOf(),
        SnapshotResources.create<ClusterLoadAssignment>(listOf(clusterLoadAssignment), "").resources(),
        mapOf(),
        SnapshotResources.create<Cluster>(listOf<Cluster>(), "").resources()
    )

    @Test
    fun `should create RBAC filter with status route permissions when no incoming permissions are defined`() {
        // given
        val rbacFilterFactoryWithStatusRoute = RBACFilterFactory(
                IncomingPermissionsProperties().also { it.enabled = true },
                StatusRouteProperties().also { it.enabled = true; it.endpoints = mutableListOf(EndpointMatch()) }
        )
        val incomingPermission = Incoming(permissionsEnabled = true)
        val expectedRbacBuilder = getRBACFilter(expectedStatusRoutePermissionsJson)

        // when
        val generated = rbacFilterFactoryWithStatusRoute.createHttpFilter(createGroup(incomingPermission), snapshot)

        // then
        assertThat(generated).isEqualTo(expectedRbacBuilder)
    }

    @Test
    fun `should create RBAC filter with two status routes permissions when no incoming permissions are defined`() {
        // given
        val rbacFilterFactoryWithStatusRoute = RBACFilterFactory(
            IncomingPermissionsProperties().also { it.enabled = true },
            StatusRouteProperties().also { it.enabled = true; it.endpoints =
                mutableListOf(
                    EndpointMatch(),
                    EndpointMatch().also { endpoint -> endpoint.path = "/example-endpoint/"; endpoint.matchingType = PathMatchingType.PATH }
                )
            }
        )
        val incomingPermission = Incoming(permissionsEnabled = true)
        val expectedRbacBuilder = getRBACFilter(expectedTwoStatusRoutesPermissionsJson)

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
        val incomingEndpoint = IncomingEndpoint(
            emptySet(),
            "/example",
            PathMatchingType.PATH,
            setOf("GET", "POST"),
            setOf(ClientWithSelector.create("client1"), ClientWithSelector.create("client2")),
            Incoming.UnlistedPolicy.LOG
        )
        val expectedShadowRules = expectedSimpleEndpointPermissionsJson(incomingEndpoint.toTestJson())

        val expectedRbacBuilder = getRBACFilterWithShadowRules(
            expectedAnyPermissionJson,
            expectedShadowRules
        )
        val incomingPermission = Incoming(
            permissionsEnabled = true,
            endpoints = listOf(
                incomingEndpoint
            ),
            unlistedEndpointsPolicy = Incoming.UnlistedPolicy.LOG
        )

        // when
        val generated = rbacFilterFactory.createHttpFilter(createGroup(incomingPermission), snapshot)

        // then
        assertThat(generated).isEqualTo(expectedRbacBuilder)
    }

    @Test
    fun `should generate RBAC rules for incoming permissions with block unlisted endpoints and log clients`() {
        // given
        val incomingEndpoint = IncomingEndpoint(
            emptySet(),
            "/example",
            PathMatchingType.PATH,
            setOf("GET", "POST"),
            setOf(ClientWithSelector.create("client1"), ClientWithSelector.create("client2")),
            Incoming.UnlistedPolicy.LOG
        )
        val expectedShadowRules = expectedSimpleEndpointPermissionsJson(incomingEndpoint.toTestJson())

        val expectedRbacBuilder = getRBACFilterWithShadowRules(
                expectedUnlistedClientsPermissions,
                expectedShadowRules
        )
        val incomingPermission = Incoming(
                permissionsEnabled = true,
                endpoints = listOf(
                    incomingEndpoint
                ),
                unlistedEndpointsPolicy = Incoming.UnlistedPolicy.BLOCKANDLOG
        )

        // when
        val generated = rbacFilterFactory.createHttpFilter(createGroup(incomingPermission), snapshot)

        // then
        assertThat(generated).isEqualTo(expectedRbacBuilder)
    }

    @Test
    fun `should generate RBAC rules for incoming permissions with log unlisted endpoints and block clients`() {
        // given
        val incomingEndpoint = IncomingEndpoint(
            emptySet(),
            "/example",
            PathMatchingType.PATH,
            setOf("GET", "POST"),
            setOf(ClientWithSelector.create("client1"), ClientWithSelector.create("client2")),
            Incoming.UnlistedPolicy.BLOCKANDLOG
        )
        val expectedShadowRules = expectedSimpleEndpointPermissionsJson(incomingEndpoint.toTestJson())

        val expectedRbacBuilder = getRBACFilterWithShadowRules(
                expectedEndpointPermissionsLogUnlistedEndpointsAndBlockUnlistedClients(incomingEndpoint.toTestJson()),
                expectedShadowRules
        )
        val incomingPermission = Incoming(
                permissionsEnabled = true,
                endpoints = listOf(
                    incomingEndpoint
                ),
                unlistedEndpointsPolicy = Incoming.UnlistedPolicy.LOG
        )

        // when
        val generated = rbacFilterFactory.createHttpFilter(createGroup(incomingPermission), snapshot)

        // then
        assertThat(generated).isEqualTo(expectedRbacBuilder)
    }

    @Test
    fun `should generate RBAC rules for incoming permissions with roles`() {
        // given
        val incomingEndpoint = IncomingEndpoint(
            emptySet(),
            "/example",
            PathMatchingType.PATH,
            setOf("GET", "POST"),
            setOf(ClientWithSelector.create("role-1"))
        )
        val expectedRbacBuilder = getRBACFilter(expectedSimpleEndpointPermissionsJson(incomingEndpoint.toTestJson()))
        val incomingPermission = Incoming(
                permissionsEnabled = true,
                endpoints = listOf(incomingEndpoint), roles = listOf(Role("role-1", setOf(ClientWithSelector.create("client1"), ClientWithSelector.create("client2"))))
        )

        // when
        val generated = rbacFilterFactory.createHttpFilter(createGroup(incomingPermission), snapshot)

        // then
        assertThat(generated).isEqualTo(expectedRbacBuilder)
    }

    @Test
    fun `should generate RBAC rules for incoming permissions with duplicated clients`() {
        // given
        val incomingEndpoint = IncomingEndpoint(
            emptySet(),
            "/example",
            PathMatchingType.PATH,
            setOf("GET", "POST"),
            setOf(
                ClientWithSelector.create("client1"),
                ClientWithSelector.create("client1"),
                ClientWithSelector.create("client1", "selector"),
                ClientWithSelector.create("client1-duplicated", "selector"),
                ClientWithSelector.create("client1-duplicated"),
                ClientWithSelector.create("role-1")
            )
        )
        val expectedRbacBuilder = getRBACFilter(expectedDuplicatedRole(incomingEndpoint.toTestJson()))
        val incomingPermission = Incoming(
                permissionsEnabled = true,
                endpoints = listOf(incomingEndpoint), roles = listOf(Role("role-1", setOf(
                        ClientWithSelector.create("client1-duplicated"),
                        ClientWithSelector.create("client1-duplicated"),
                        ClientWithSelector.create("client2"))
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
        val incomingEndpoints = listOf(
            IncomingEndpoint(
                emptySet(),
                "/example",
                PathMatchingType.PATH,
                setOf("GET"),
                setOf(ClientWithSelector.create("client1"))
            ), IncomingEndpoint(
                emptySet(),
                "/example2",
                PathMatchingType.PATH,
                setOf("POST"),
                setOf(ClientWithSelector.create("client2"))
            )
        )
        val expectedRbacBuilder = getRBACFilter(expectedEndpointPermissionsWithDifferentRulesForDifferentClientsJson(incomingEndpoints.map { it.toTestJson() }))
        val incomingPermission = Incoming(
                permissionsEnabled = true,
                endpoints = incomingEndpoints
        )

        // when
        val generated = rbacFilterFactory.createHttpFilter(createGroup(incomingPermission), snapshot)

        // then
        assertThat(generated).isEqualTo(expectedRbacBuilder)
    }

    @Test
    fun `should generate minimal RBAC rules for incoming permissions with roles and clients`() {
        // given
        val incomingPermission = Incoming(
                permissionsEnabled = true,
                endpoints = listOf(IncomingEndpoint(
                        emptySet(),
                        "/example",
                        PathMatchingType.PATH,
                        setOf("GET", "POST"),
                        setOf(ClientWithSelector.create("role-1"))
                ), IncomingEndpoint(
                        emptySet(),
                        "/example2",
                        PathMatchingType.PATH,
                        setOf("GET", "POST"),
                        setOf(ClientWithSelector.create("client2"), ClientWithSelector.create("client1"))
                )), roles = listOf(Role("role-1", setOf(ClientWithSelector.create("client1"), ClientWithSelector.create("client2"))))
        )
        val expectedRbacBuilder = getRBACFilter(expectedTwoClientsSimpleEndpointPermissionsJson(
            incomingPermission.endpoints[0].toTestJson(), incomingPermission.endpoints[1].toTestJson()
        ))

        // when
        val generated = rbacFilterFactory.createHttpFilter(createGroup(incomingPermission), snapshot)

        // then
        assertThat(generated).isEqualTo(expectedRbacBuilder)
    }

    @Test
    fun `should generate minimal RBAC rules for incoming permissions with roles and single client`() {
        // given
        val incomingPermission = Incoming(
                permissionsEnabled = true,
                endpoints = listOf(IncomingEndpoint(
                        emptySet(),
                        "/example",
                        PathMatchingType.PATH,
                        setOf("GET", "POST"),
                        setOf(ClientWithSelector.create("client2"), ClientWithSelector.create("role-1"))
                ), IncomingEndpoint(
                        emptySet(),
                        "/example2",
                        PathMatchingType.PATH,
                        setOf("GET", "POST"),
                        setOf(ClientWithSelector.create("role-2"), ClientWithSelector.create("client1"))
                )), roles = listOf(Role("role-1", setOf(ClientWithSelector.create("client1"))), Role("role-2", setOf(ClientWithSelector.create("client2"))))
        )
        val expectedRbacBuilder = getRBACFilter(expectedTwoClientsSimpleEndpointPermissionsJson(
            incomingPermission.endpoints[0].toTestJson(), incomingPermission.endpoints[1].toTestJson()
        ))

        // when
        val generated = rbacFilterFactory.createHttpFilter(createGroup(incomingPermission), snapshot)

        // then
        assertThat(generated).isEqualTo(expectedRbacBuilder)
    }

    @Test
    fun `should generate RBAC rules for incoming permissions with source ip authentication`() {
        // given
        val incomingEndpoint = IncomingEndpoint(
            emptySet(),
            "/example",
            PathMatchingType.PATH,
            setOf("GET", "POST"),
            setOf(ClientWithSelector.create("client1"), ClientWithSelector.create("client2"))
        )
        val expectedRbacBuilder = getRBACFilter(expectedSourceIpAuthPermissionsJson(incomingEndpoint.toTestJson()))
        val incomingPermission = Incoming(
                permissionsEnabled = true,
                endpoints = listOf(incomingEndpoint)
        )

        // when
        val generated = rbacFilterFactoryWithSourceIpAuth.createHttpFilter(createGroup(incomingPermission), snapshotForSourceIpAuth)

        // then
        assertThat(generated).isEqualTo(expectedRbacBuilder)
    }

    @Test
    fun `should generate RBAC rules for incoming permissions without clients`() {
        // given
        val incomingEndpoint = IncomingEndpoint(
            emptySet(),
            "/example",
            PathMatchingType.PATH,
            setOf()
        )
        val incomingPermission = Incoming(
                permissionsEnabled = true,
                endpoints = listOf(incomingEndpoint)
        )
        val expectedPolicies = expectedDenyForAllEndpointPermissions(policyName = incomingPermission.endpoints[0].toTestJson())
        val expectedRbacBuilder = getRBACFilter(expectedPolicies)

        // when
        val generated = rbacFilterFactory.createHttpFilter(createGroup(incomingPermission), snapshot)

        // then
        assertThat(generated).isEqualTo(expectedRbacBuilder)
    }

    @Test
    fun `should correctly map endpoint without clients when unlistedEndpointsPolicy is set to log`() {
        val incomingEndpoint = IncomingEndpoint(
            path = "/example",
            clients = setOf(),
            unlistedClientsPolicy = Incoming.UnlistedPolicy.BLOCKANDLOG
        )
        // given
        val expectedActual = """
        {
          "policies": {
            "${incomingEndpoint.toTestJson()}": {
              "permissions": [{
                "and_rules": {
                  "rules": [ ${pathRule("/example")} ]
                }
              }],
              "principals": [{ "not_id": $anyTrue }]
            },
            "ALLOW_UNLISTED_POLICY": {
              "permissions": [{
                "not_rule": {
                  "or_rules": {
                    "rules": [{
                      "and_rules": {
                        "rules": [ ${pathRule("/example")} ]
                      }
                    }]
                  }
                }
              }],
              "principals": [ $anyTrue ]
            }
          }
        }
        """

        val incomingPermissions = Incoming(
            permissionsEnabled = true,
            unlistedEndpointsPolicy = Incoming.UnlistedPolicy.LOG,
            endpoints = listOf(incomingEndpoint)
        )

        val expectedShadow = expectedDenyForAllEndpointPermissions(policyName = incomingPermissions.endpoints[0].toTestJson())
        val expectedRbacBuilder = getRBACFilterWithShadowRules(expectedActual, expectedShadow)

        // when
        val generated = rbacFilterFactory.createHttpFilter(createGroup(incomingPermissions), snapshot)

        // then
        assertThat(generated).isEqualTo(expectedRbacBuilder)
    }

    @Test
    fun `should correctly map endpoint without clients when unlistedClientsPolicy is set to log`() {
        // given
        val expectedActual = """
        {
          "policies": {
            "ALLOW_LOGGED_POLICY": {
              "permissions": [{
                "or_rules": {
                  "rules": [{
                    "and_rules": {
                      "rules": [ ${pathRule("/example")} ]
                    }
                  }]
                }
              }],
              "principals": [ $anyTrue ]
            }
          }
        }
        """

        val incomingPermissions = Incoming(
            permissionsEnabled = true,
            unlistedEndpointsPolicy = Incoming.UnlistedPolicy.BLOCKANDLOG,
            endpoints = listOf(IncomingEndpoint(
                path = "/example",
                clients = setOf(),
                unlistedClientsPolicy = Incoming.UnlistedPolicy.LOG
            ))
        )

        val expectedShadow = expectedDenyForAllEndpointPermissions(policyName = incomingPermissions.endpoints[0].toTestJson())
        val expectedRbacBuilder = getRBACFilterWithShadowRules(expectedActual, expectedShadow)

        // when
        val generated = rbacFilterFactory.createHttpFilter(createGroup(incomingPermissions), snapshot)

        // then
        assertThat(generated).isEqualTo(expectedRbacBuilder)
    }

    @Test
    fun `should generate RBAC rules for incoming permissions with static ip range authentication`() {
        // given
        val incomingEndpoint = IncomingEndpoint(
            emptySet(),
            "/example",
            PathMatchingType.PATH,
            setOf("GET"),
            setOf(ClientWithSelector.create("client1"))
        )
        val expectedRbacBuilder = getRBACFilter(expectedSourceIpAuthWithStaticRangeJson(incomingEndpoint.toTestJson()))
        val incomingPermission = Incoming(
                permissionsEnabled = true,
                endpoints = listOf(incomingEndpoint)
        )

        // when
        val generated = rbacFilterFactoryWithStaticRange.createHttpFilter(createGroup(incomingPermission), snapshot)

        // then
        assertThat(generated).isEqualTo(expectedRbacBuilder)
    }

    @Test
    fun `should generate RBAC rules for incoming permissions with static ip range authentication and client source ip`() {
        // given
        val incomingEndpoint = IncomingEndpoint(
            emptySet(),
            "/example",
            PathMatchingType.PATH,
            setOf("GET"),
            setOf(ClientWithSelector.create("client1"), ClientWithSelector.create("client2"))
        )
        val expectedRbacBuilder = getRBACFilter(expectedSourceIpAuthWithStaticRangeAndSourceIpJson(incomingEndpoint.toTestJson()))
        val incomingPermission = Incoming(
                permissionsEnabled = true,
                endpoints = listOf(incomingEndpoint)
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
        val incomingEndpoint = IncomingEndpoint(
            emptySet(),
            "/example",
            PathMatchingType.PATH,
            setOf("GET"),
            setOf(ClientWithSelector.create("client2", "selector"))
        )
        val expectedRbacBuilder = getRBACFilter(expectedSourceIpWithSelectorAuthPermissionsJson(incomingEndpoint.toTestJson()))
        val incomingPermission = Incoming(
                permissionsEnabled = true,
                endpoints = listOf(incomingEndpoint)
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
        val incomingEndpoint = IncomingEndpoint(
            emptySet(),
            "/example",
            PathMatchingType.PATH,
            setOf("GET"),
            setOf(ClientWithSelector.create("client1", "selector"))
        )
        val expectedRbacBuilder = getRBACFilter(expectedSourceIpFromDiscoveryWithSelectorAuthPermissionsJson(incomingEndpoint.toTestJson()))
        val incomingPermission = Incoming(
                permissionsEnabled = true,
                endpoints = listOf(incomingEndpoint)
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
        val incomingEndpoint = IncomingEndpoint(
            emptySet(),
            "/example",
            PathMatchingType.PATH,
            setOf("GET"),
            setOf(ClientWithSelector.create("role1"))
        )
        val expectedRbacBuilder = getRBACFilter(expectedSourceIpWithStaticRangeAndSelectorAuthPermissionsAndRolesJson(incomingEndpoint.toTestJson()))
        val incomingPermission = Incoming(
                permissionsEnabled = true,
                endpoints = listOf(incomingEndpoint),
                roles = listOf(Role("role1", setOf(
                        ClientWithSelector.create("client1", "selector1"),
                        ClientWithSelector.create("client2", "selector2"))
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
    fun `should generate RBAC rules for incoming permissions with client allowed to all endpoints`() {
        // given
        val incomingEndpoint = IncomingEndpoint(
            emptySet(),
            "/example",
            PathMatchingType.PATH,
            setOf("GET"),
            setOf(ClientWithSelector.create("client1"))
        )
        val expectedRbacBuilder = getRBACFilterWithShadowRules(expectedRulesForAllowedClient(incomingEndpoint.toTestJson()), expectedShadowRulesForAllowedClient(incomingEndpoint.toTestJson()))
        val incomingPermission = Incoming(
            permissionsEnabled = true,
            endpoints = listOf(incomingEndpoint)
        )

        // when
        val generated = rbacFilterFactoryWithAllowAllEndpointsForClient.createHttpFilter(
            createGroup(incomingPermission),
            snapshotForSourceIpAuth
        )

        // then
        assertThat(generated).isEqualTo(expectedRbacBuilder)
    }

    @Test
    fun `should generate RBAC rules for incoming permissions with default client list`() {
        // given
        val incomingEndpoint = IncomingEndpoint(
            emptySet(),
            "/default",
            PathMatchingType.PATH,
            setOf("GET"),
            setOf(ClientWithSelector.create("client1"))
        )
        val incomingPermission = Incoming(
            permissionsEnabled = true,
            endpoints = listOf(incomingEndpoint)
        )

        val rules = expectedPoliciesForDefaultAndCustomLists(incomingEndpoint.toTestJson(), listOf("client1", "default-client", "xyz"), "/default")
        val expectedDefaultRbacBuilder = getRBACFilterWithShadowRules(rules, rules)

        // when
        val generated = rbacFilterFactoryWithDefaultAndCustomClientsLists.createHttpFilter(createGroup(incomingPermission), snapshot)

        // then
        assertThat(generated).isEqualTo(expectedDefaultRbacBuilder)
    }

    @Test
    fun `should generate RBAC rules for incoming permissions with custom client list`() {
        // given
        val incomingEndpoint = IncomingEndpoint(
            emptySet(),
            "/custom",
            PathMatchingType.PATH,
            setOf("GET"),
            setOf(ClientWithSelector.create("client1"), ClientWithSelector.create("custom1"))
        )
        val incomingPermission = Incoming(
            permissionsEnabled = true,
            endpoints = listOf(incomingEndpoint)
        )

        val rules = expectedPoliciesForDefaultAndCustomLists(incomingEndpoint.toTestJson(), listOf("client1", "custom1-client", "xyz"), "/custom")
        val expectedDefaultRbacBuilder = getRBACFilterWithShadowRules(rules, rules)

        // when
        val generated = rbacFilterFactoryWithDefaultAndCustomClientsLists.createHttpFilter(createGroup(incomingPermission), snapshot)

        // then
        assertThat(generated).isEqualTo(expectedDefaultRbacBuilder)
    }

    private fun expectedEndpointPermissionsWithDifferentRulesForDifferentClientsJson(policyNames: List<String>) = """
        {
          "policies": {
            "${policyNames[0]}": {
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
                ${originalAndAuthenticatedPrincipal("client1")}
              ]
            },
            "${policyNames[1]}": {
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
                ${originalAndAuthenticatedPrincipal("client2")}
              ]
            }
          }
        }
    """

    private fun expectedSourceIpFromDiscoveryWithSelectorAuthPermissionsJson(policyName: String) = """
        {
          "policies": {
            "$policyName": {
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

    private fun expectedSourceIpWithSelectorAuthPermissionsJson(policyName: String) = """
        {
          "policies": {
            "$policyName": {
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

    private fun expectedSourceIpWithStaticRangeAndSelectorAuthPermissionsAndRolesJson(policyName: String): String = """
        {
          "policies": {
            "$policyName": {
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

    private fun expectedSourceIpAuthPermissionsJson(policyName: String) = """
        {
          "policies": {
            "$policyName": {
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
                ${originalAndAuthenticatedPrincipal(("client2"))}
              ]
            }
          }
        }
    """

    private fun expectedSimpleEndpointPermissionsJson(policyName: String) = """
        {
          "policies": {
            "$policyName": {
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
                ${originalAndAuthenticatedPrincipal(("client1"))},
                ${originalAndAuthenticatedPrincipal(("client2"))}
              ]
            }
          }
        }
    """

    private fun expectedDuplicatedRole(policyName: String) = """
        {
          "policies": {
           """ /* notice that duplicated clients occurs only once here */ + """
            "$policyName": {
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
                ${originalAndAuthenticatedPrincipal(("client1"))},
                ${originalAndAuthenticatedPrincipal(("client1-duplicated"))},
                ${originalAndAuthenticatedPrincipal(("client2"))}
              ]
            }
          }
        }
    """

    private fun expectedTwoClientsSimpleEndpointPermissionsJson(vararg policyNames: String) = """
        {
          "policies": {
            "${policyNames[0]}": {
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
                ${originalAndAuthenticatedPrincipal(("client1"))},
                ${originalAndAuthenticatedPrincipal(("client2"))}
              ]
            },
            "${policyNames[1]}": {
              "permissions": [
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
                ${originalAndAuthenticatedPrincipal(("client1"))},
                ${originalAndAuthenticatedPrincipal(("client2"))}
              ]
            }
          }
        }
    """

    private val anyTrue = """{ "any": "true"}"""

    private val expectedStatusRoutePermissionsJson = """
        {
          "policies": {
            "STATUS_ALLOW_ALL_POLICY": {
              "permissions": [{
                "or_rules": {
                    "rules": [
                        ${pathPrefixRule("/status/")}
                    ]
                }
              }], "principals": [
                $anyTrue
              ]
            }
          }
        }
    """

    private val expectedTwoStatusRoutesPermissionsJson = """
        {
          "policies": {
            "STATUS_ALLOW_ALL_POLICY": {
              "permissions": [{
                "or_rules": {
                    "rules": [
                        ${pathPrefixRule("/status/")},
                        ${pathRule("/example-endpoint/")}
                    ]
                }
              }], "principals": [
                $anyTrue
              ]
            }
          }
        }
    """

    private val expectedAnyPermissionJson = """
        {
          "policies": {
            "ALLOW_LOGGED_POLICY": {
              "permissions": [{
                "or_rules": {
                  "rules": [
                    {
                      "and_rules": {
                        "rules": [
                          {
                            "url_path": {
                              "path": {
                                "exact": "/example"
                              }
                            }
                          },
                          {
                            "or_rules": {
                              "rules": [
                                {
                                  "header": {
                                    "name": ":method",
                                    "exact_match": "GET"
                                  }
                                },
                                {
                                  "header": {
                                    "name": ":method",
                                    "exact_match": "POST"
                                  }
                                }
                              ]
                            }
                          }
                        ]
                      }
                    }
                  ]
                }
              }],
              "principals": [ $anyTrue ]
            },
            "ALLOW_UNLISTED_POLICY": {
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

    private fun expectedDenyForAllEndpointPermissions(policyName: String) = """
    {
      "policies": {
        "$policyName": {
          "permissions": [{
            "and_rules": {
              "rules": [ ${pathRule("/example")} ]
            }
          }],
          "principals": [{ "not_id": $anyTrue }]
        }
      }
    }    
    """

    private val expectedUnlistedClientsPermissions = """{ "policies": {
            "ALLOW_LOGGED_POLICY": {
              "permissions": [
              {
                  "or_rules": {
                    "rules": [{
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
                    ]}
              }
              ], "principals": [
                $anyTrue
              ]
            }
        } }""".trimMargin()

    private fun expectedSourceIpAuthWithStaticRangeJson(policyName: String) = """
        {
          "policies": {
            "$policyName": {
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

    private fun expectedSourceIpAuthWithStaticRangeAndSourceIpJson(policyName: String) = """
        {
          "policies": {
            "$policyName": {
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

    private fun expectedEndpointPermissionsLogUnlistedEndpointsAndBlockUnlistedClients(policyName: String) = """
        {
          "policies": {
            "$policyName": {
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
                ${originalAndAuthenticatedPrincipal(("client1"))},
                ${originalAndAuthenticatedPrincipal(("client2"))}
              ]
            },
            "ALLOW_UNLISTED_POLICY": {
              "permissions": [
              {
                  "not_rule": {
                      "or_rules": {
                        "rules": [{
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
                    ]}
                  }
              }
              ], "principals": [
                $anyTrue
              ]
            }
          }
        }
    """

    private fun expectedPoliciesForAllowedClient(policyName: String, principals: String) = """
        {
          "policies": {
            "$policyName": {
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
              ], "principals": [ $principals ]
            }
          }
        }
    """

    private fun expectedPoliciesForDefaultAndCustomLists(policyName: String, principals: List<String>, path: String) = """
        {
          "policies": {
            "$policyName": {
              "permissions": [
                {
                  "and_rules": {
                    "rules": [
                      ${pathRule(path)},
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
              ], "principals": [ ${principals.joinToString(", ") { originalAndAuthenticatedPrincipal(it) }} ]
            }
          }
        }
    """
    private fun expectedRulesForAllowedClient(policyName: String) = expectedPoliciesForAllowedClient(
        policyName,
        "${originalAndAuthenticatedPrincipal("client1")}, ${originalAndAuthenticatedPrincipal("allowed-client")}"
    )

    private fun expectedShadowRulesForAllowedClient(policyName: String) = expectedPoliciesForAllowedClient(
        policyName,
        originalAndAuthenticatedPrincipal("client1")
    )

    private fun IncomingEndpoint.toTestJson() : String {
        return this.toJson().replace("\"", "\\\"")
    }
}
