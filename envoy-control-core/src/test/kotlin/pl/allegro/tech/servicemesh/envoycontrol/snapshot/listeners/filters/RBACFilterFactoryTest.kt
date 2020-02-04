package pl.allegro.tech.servicemesh.envoycontrol.snapshot.listeners.filters

import com.google.protobuf.Any
import com.google.protobuf.util.JsonFormat
import io.envoyproxy.envoy.config.filter.network.http_connection_manager.v2.HttpFilter
import io.envoyproxy.envoy.config.filter.http.rbac.v2.RBAC as RBACFilter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.groups.Incoming
import pl.allegro.tech.servicemesh.envoycontrol.groups.IncomingEndpoint
import pl.allegro.tech.servicemesh.envoycontrol.groups.PathMatchingType
import pl.allegro.tech.servicemesh.envoycontrol.groups.ProxySettings
import pl.allegro.tech.servicemesh.envoycontrol.groups.Role
import pl.allegro.tech.servicemesh.envoycontrol.groups.ServicesGroup
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.IncomingPermissionsProperties

internal class RBACFilterFactoryTest {
    private val rbacFilterFactory = RBACFilterFactory(IncomingPermissionsProperties().also { it.enabled = true })

    private val expectedSimpleEndpointPermissionsJson = """
        {
          "policies": {
            "client1,client2": {
              "permissions": [
                {
                  "and_rules": {
                    "rules": [
                      ${headerRule("/example")},
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
                      ${headerRule("/example")},
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
                      ${headerRule("/example2")},
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

    private val exptectedEndpointPermissionsWithoutMethodsJson = """
        {
          "policies": {
            "client1,client2": {
              "permissions": [
                {
                  "and_rules": {
                    "rules": [
                      ${headerRule("/example")}
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

    private val exptectedEndpointPermissionsWithoutClientsJson = """{ "policies": {} }"""

    private fun headerRule(path: String): String {
        return """{
            "header": {
               "name": ":path",
               "exact_match": "$path"
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

    private fun principalHeader(header: String, principal: String): String {
        val s = """{
                    "header": {
                      "name": "$header",
                      "exact_match": "$principal"
                    }
                }"""

        return s
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

    private fun createGroup(serviceName: String, incomingPermission: Incoming): ServicesGroup {
        val group = ServicesGroup(
                ads = false,
                serviceName = serviceName,
                proxySettings = ProxySettings(incoming = incomingPermission.copy(permissionsEnabled = true))
        )
        return group
    }

    @Test
    fun `should generate RBAC rules for incoming permissions with roles`() {
        // given
        val rbacBuilder = getRBACFilter(expectedSimpleEndpointPermissionsJson)
        val incomingPermission = Incoming(
                endpoints = listOf(IncomingEndpoint(
                        "/example",
                        PathMatchingType.PATH,
                        setOf("GET", "POST"),
                        setOf("role-1")
                )), roles = listOf(Role("role-1", setOf("client1", "client2")))
        )

        // when
        val generated = rbacFilterFactory.createHttpFilter(createGroup("some-service", incomingPermission))

        // then
        assertThat(generated).isEqualTo(rbacBuilder)
    }

    @Test
    fun `should generate minimal RBAC rules for incoming permissions with roles and clients`() {
        // given
        val rbacBuilder = getRBACFilter(expectedTwoClientsSimpleEndpointPermissionsJson)
        val incomingPermission = Incoming(
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
        val generated = rbacFilterFactory.createHttpFilter(createGroup("some-service", incomingPermission))

        // then
        assertThat(generated).isEqualTo(rbacBuilder)
    }

    @Test
    fun `should generate minimal RBAC rules for incoming permissions with roles and single client`() {
        // given
        val rbacBuilder = getRBACFilter(expectedTwoClientsSimpleEndpointPermissionsJson)
        val incomingPermission = Incoming(
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
        val generated = rbacFilterFactory.createHttpFilter(createGroup("some-service", incomingPermission))

        // then
        assertThat(generated).isEqualTo(rbacBuilder)
    }

    @Test
    fun `should generate RBAC rules for incoming permissions with two endpoints containing methods and clients`() {
        // given
        val rbacBuilder = getRBACFilter(expectedTwoClientsSimpleEndpointPermissionsJson)
        val incomingPermission = Incoming(
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
        val generated = rbacFilterFactory.createHttpFilter(createGroup("some-service", incomingPermission))

        // then
        assertThat(generated).isEqualTo(rbacBuilder)
    }

    @Test
    fun `should generate RBAC rules for incoming permissions with methods and clients`() {
        // given
        val rbacBuilder = getRBACFilter(expectedSimpleEndpointPermissionsJson)
        val incomingPermission = Incoming(
                endpoints = listOf(IncomingEndpoint(
                        "/example",
                        PathMatchingType.PATH,
                        setOf("GET", "POST"),
                        setOf("client1", "client2")
                ))
        )

        // when
        val generated = rbacFilterFactory.createHttpFilter(createGroup("some-service", incomingPermission))

        // then
        assertThat(generated).isEqualTo(rbacBuilder)
    }

    @Test
    fun `should generate RBAC rules for incoming permissions without methods defined`() {
        // given
        val rbacBuilder = getRBACFilter(exptectedEndpointPermissionsWithoutMethodsJson)
        val incomingPermission = Incoming(
                endpoints = listOf(IncomingEndpoint(
                        "/example",
                        PathMatchingType.PATH,
                        setOf(),
                        setOf("client1", "client2")
                ))
        )

        // when
        val generated = rbacFilterFactory.createHttpFilter(createGroup("some-service", incomingPermission))

        // then
        assertThat(generated).isEqualTo(rbacBuilder)
    }

    @Test
    fun `should generate RBAC rules for incoming permissions without clients`() {
        // given
        val rbacBuilder = getRBACFilter(exptectedEndpointPermissionsWithoutClientsJson)
        val incomingPermission = Incoming(
                endpoints = listOf(IncomingEndpoint(
                        "/example",
                        PathMatchingType.PATH,
                        setOf("GET", "POST"),
                        setOf()
                ))
        )

        // when
        val generated = rbacFilterFactory.createHttpFilter(createGroup("some-service", incomingPermission))

        // then
        assertThat(generated).isEqualTo(rbacBuilder)
    }
}
