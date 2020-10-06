package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters

import com.google.protobuf.util.JsonFormat
import io.envoyproxy.envoy.config.rbac.v3.Permission
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.groups.IncomingEndpoint
import pl.allegro.tech.servicemesh.envoycontrol.groups.PathMatchingType

internal class RBACFilterPermissionsTest {

    private val rbacFilterPermissions = RBACFilterPermissions()

    @Test
    fun `should create permissions with exact path matcher`() {
        // given
        val endpoint = IncomingEndpoint(
            path = "/example",
            pathMatchingType = PathMatchingType.PATH,
            methods = setOf()
        )
        // language=json
        val expectedPermission = """{
            "and_rules": {
              "rules": [
                {
                  "url_path": {
                    "path": {
                      "exact": "/example"
                    }
                  }
                }
              ]
            }
        }""".asPermission()

        // when
        val permissions = rbacFilterPermissions.createCombinedPermissions(endpoint).build()

        // then
        assertThat(permissions).isEqualTo(expectedPermission)
    }

    @Test
    fun `should create permissions with prefix path matcher`() {
        // given
        val endpoint = IncomingEndpoint(
            path = "/example",
            pathMatchingType = PathMatchingType.PATH_PREFIX,
            methods = setOf()
        )
        // language=json
        val expectedPermission = """{
            "and_rules": {
              "rules": [
                {
                  "url_path": {
                    "path": {
                      "prefix": "/example"
                    }
                  }
                }
              ]
            }
        }""".asPermission()

        // when
        val permissions = rbacFilterPermissions.createCombinedPermissions(endpoint).build()

        // then
        assertThat(permissions).isEqualTo(expectedPermission)
    }

    @Test
    fun `should create permissions with regex path matcher`() {
        // given
        val endpoint = IncomingEndpoint(
            path = "/regex",
            pathMatchingType = PathMatchingType.PATH_REGEX,
            methods = setOf()
        )
        // language=json
        val expectedPermission = """{
            "and_rules": {
              "rules": [
                {
                  "url_path": {
                    "path": {
                      "safe_regex": {
                        "google_re2": {
                        },
                        "regex": "/regex"
                      }
                    }
                  }
                }
              ]
            }
        }""".asPermission()

        // when
        val permissions = rbacFilterPermissions.createCombinedPermissions(endpoint).build()

        // then
        assertThat(permissions).isEqualTo(expectedPermission)
    }

    @Test
    fun `should create permissions with method matchers`() {
        // given
        val endpoint = IncomingEndpoint(
            path = "/example",
            pathMatchingType = PathMatchingType.PATH,
            methods = setOf("PUT", "DELETE")
        )
        // language=json
        val expectedPermission = """{
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
                          "exact_match": "PUT"
                        }
                      }, 
                      {
                        "header": {
                          "name": ":method",
                          "exact_match": "DELETE"
                        }
                      }
                    ]
                  }
                }
              ]
            }
        }""".asPermission()

        // when
        val permissions = rbacFilterPermissions.createCombinedPermissions(endpoint).build()

        // then
        assertThat(permissions).isEqualTo(expectedPermission)
    }

    private fun String.asPermission(): Permission {
        val builder = Permission.newBuilder()
        JsonFormat.parser().merge(this, builder)
        return builder.build()
    }
}
