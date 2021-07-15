package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters.rbac

import com.google.protobuf.Any
import com.google.protobuf.util.JsonFormat
import io.envoyproxy.envoy.extensions.filters.http.rbac.v3.RBAC
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter
import pl.allegro.tech.servicemesh.envoycontrol.groups.CommunicationMode
import pl.allegro.tech.servicemesh.envoycontrol.groups.Incoming
import pl.allegro.tech.servicemesh.envoycontrol.groups.ProxySettings
import pl.allegro.tech.servicemesh.envoycontrol.groups.ServicesGroup

interface RBACFilterFactoryTestUtils {

    fun pathRule(path: String): String {
        return """{
            "url_path": {
               "path": {
                    "exact": "$path"
               }
            }
        }"""
    }

    fun pathPrefixRule(prefix: String): String {
        return """{
            "url_path": {
               "path": {
                    "prefix": "$prefix"
               }
            }
        }"""
    }

    fun methodRule(method: String): String {
        return """{
           "header": {
              "name": ":method",
              "exact_match": "$method"
           }
        }"""
    }

    fun principalSourceIp(address: String, prefixLen: Int = 32): String {
        return """{
                "direct_remote_ip": {
                  "address_prefix": "$address",
                  "prefix_len": $prefixLen
                }
            }
        """
    }

    fun principalHeader(name: String, value: String): String {
        return """{
                    "header": {
                      "name": "$name",
                      "exact_match": "$value"
                    }
                }"""
    }

    fun authenticatedPrincipal(value: String): String {
        return """{
                    "authenticated": {
                      "principal_name": {
                        "exact": "spiffe://$value"
                      }
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

    fun getRBACFilter(json: String): HttpFilter {
        return getRBACFilterWithShadowRules(json, json)
    }

    fun getRBACFilterWithShadowRules(rules: String, shadowRules: String): HttpFilter {
        val rbacFilter = RBAC.newBuilder()
        JsonFormat.parser().merge(wrapInFilter(rules), rbacFilter)
        JsonFormat.parser().merge(wrapInFilterShadow(shadowRules), rbacFilter)
        return HttpFilter.newBuilder()
            .setName("envoy.filters.http.rbac")
            .setTypedConfig(Any.pack(rbacFilter.build()))
            .build()
    }

    fun createGroup(
        incomingPermission: Incoming? = null,
        serviceName: String = "some-service"
    ) = ServicesGroup(
        communicationMode = CommunicationMode.ADS,
        serviceName = serviceName,
        proxySettings = ProxySettings(
            incoming = incomingPermission ?: Incoming()
        )
    )
}
