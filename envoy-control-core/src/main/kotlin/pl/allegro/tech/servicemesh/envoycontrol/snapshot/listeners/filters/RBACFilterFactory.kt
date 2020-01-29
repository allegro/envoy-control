package pl.allegro.tech.servicemesh.envoycontrol.snapshot.listeners.filters

import com.google.protobuf.Any
import io.envoyproxy.envoy.api.v2.route.HeaderMatcher
import io.envoyproxy.envoy.config.filter.http.rbac.v2.RBAC as RBACFilter
import io.envoyproxy.envoy.config.rbac.v2.Permission
import io.envoyproxy.envoy.config.rbac.v2.Policy
import io.envoyproxy.envoy.config.rbac.v2.Principal
import io.envoyproxy.envoy.config.rbac.v2.RBAC
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group
import io.envoyproxy.envoy.config.filter.network.http_connection_manager.v2.HttpFilter

import pl.allegro.tech.servicemesh.envoycontrol.groups.Incoming
import pl.allegro.tech.servicemesh.envoycontrol.groups.PathMatchingType
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.IncomingPermissionsProperties

class RBACFilterFactory(
    private val properties: IncomingPermissionsProperties
) {
    fun getRules(incomingPermissions: Incoming): RBAC {
        val clientToPolicyBuilder = mutableMapOf<String, Policy.Builder>()

        incomingPermissions.endpoints.forEach { incomingEndpoint ->
            val policy = Policy.newBuilder()
            val clientName = incomingEndpoint.clients.joinToString(",")

            val pathMatch = when (incomingEndpoint.pathMatchingType) {
                PathMatchingType.PATH -> HeaderMatcher.newBuilder()
                        .setName(":path").setExactMatch(incomingEndpoint.path).build()
                PathMatchingType.PATH_PREFIX -> HeaderMatcher.newBuilder()
                        .setName(":path").setPrefixMatch(incomingEndpoint.path).build()
            }

            val pathPermission = Permission.newBuilder().setHeader(pathMatch)
            val combined = Permission.newBuilder()
            val methodPermissions = Permission.newBuilder()

            incomingEndpoint.clients.forEach { client ->
                val clientMatch = HeaderMatcher.newBuilder()
                        .setName(properties.clientIdentityHeader).setExactMatch(client).build()
                val principal = Principal.newBuilder().setHeader(clientMatch)

                policy.addPrincipals(principal)
            }

            val methodPermissionSet = Permission.Set.newBuilder()

            incomingEndpoint.methods.forEach { method ->
                val methodMatch = HeaderMatcher.newBuilder().setName(":method").setExactMatch(method).build()
                methodPermissionSet.addRules(Permission.newBuilder().setHeader(methodMatch).build())
            }

            methodPermissions.setOrRules(methodPermissionSet.build()).build()

            combined.setAndRules( Permission.Set.newBuilder().addAllRules(listOf(pathPermission.build(), methodPermissions.build())))
            policy.addPermissions(combined)

            clientToPolicyBuilder[clientName] = policy
        }

        val clientToPolicy = mutableMapOf<String, Policy>()
        clientToPolicyBuilder.forEach { (client, policyBuilder) ->
            clientToPolicy[client] = policyBuilder.build()
        }

        val rbac = RBAC.newBuilder()
                .setAction(RBAC.Action.ALLOW)
                .putAllPolicies(clientToPolicy)
                .build()
        return rbac
    }

    fun createHttpFilter(group: Group): HttpFilter? {
        return if (properties.enabled) {
            val rules = getRules(group.proxySettings.incoming)
            val rbacFilter = RBACFilter.newBuilder().setRules(rules).build()
            HttpFilter.newBuilder().setName("envoy.filters.http.rbac").setTypedConfig(Any.pack(rbacFilter)).build()
        } else {
            null
        }
    }
}
