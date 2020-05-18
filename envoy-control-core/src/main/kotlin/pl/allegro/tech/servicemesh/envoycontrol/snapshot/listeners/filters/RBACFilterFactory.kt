package pl.allegro.tech.servicemesh.envoycontrol.snapshot.listeners.filters

import com.google.protobuf.Any
import com.google.protobuf.UInt32Value
import io.envoyproxy.envoy.api.v2.core.CidrRange
import io.envoyproxy.envoy.api.v2.route.HeaderMatcher
import io.envoyproxy.envoy.config.filter.network.http_connection_manager.v2.HttpFilter
import io.envoyproxy.envoy.config.rbac.v2.Permission
import io.envoyproxy.envoy.config.rbac.v2.Policy
import io.envoyproxy.envoy.config.rbac.v2.Principal
import io.envoyproxy.envoy.config.rbac.v2.RBAC
import io.envoyproxy.envoy.type.matcher.PathMatcher
import io.envoyproxy.envoy.type.matcher.StringMatcher
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group
import pl.allegro.tech.servicemesh.envoycontrol.groups.Incoming
import pl.allegro.tech.servicemesh.envoycontrol.groups.IncomingEndpoint
import pl.allegro.tech.servicemesh.envoycontrol.groups.PathMatchingType
import pl.allegro.tech.servicemesh.envoycontrol.groups.Role
import pl.allegro.tech.servicemesh.envoycontrol.logger
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.AdditionalAuthenticationMethod
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.GlobalSnapshot
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.IncomingPermissionsProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.Matching
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.StatusRouteProperties
import io.envoyproxy.envoy.config.filter.http.rbac.v2.RBAC as RBACFilter

typealias Selector = String

class RBACFilterFactory(
    private val incomingPermissionsProperties: IncomingPermissionsProperties,
    statusRouteProperties: StatusRouteProperties
) {
    companion object {
        private val logger by logger()
        private const val ANY_PRINCIPAL_NAME = "_ANY_"
        private val EXACT_IP_MASK = UInt32Value.of(32)
    }

    private val incomingServicesSourceAuthentication = incomingPermissionsProperties
            .sourceIpAuthentication
            .ipFromServiceDiscovery
            .enabledForIncomingServices

    private val statusRoutePrincipal = createStatusRoutePrincipal(statusRouteProperties)
    private val staticIpRanges = createStaticIpRanges()

    private fun getRules(serviceName: String, incomingPermissions: Incoming, snapshot: GlobalSnapshot): RBAC {
        val clientToPolicyBuilder = mutableMapOf<String, Policy.Builder>()

        if (statusRoutePrincipal != null) {
            clientToPolicyBuilder[ANY_PRINCIPAL_NAME] = statusRoutePrincipal
        }

        incomingPermissions.endpoints.forEach { incomingEndpoint ->
            if (incomingEndpoint.clients.isEmpty()) {
                logger.warn("An incoming endpoint definition for $serviceName does not have any clients defined." +
                        "It means that no one will be able to contact that endpoint.")
                return@forEach
            }

            val clients = resolveClients(incomingEndpoint, incomingPermissions.roles)
            val principals = clients.flatMap { mapClientToPrincipals(it, snapshot) }
            if (principals.isNotEmpty()) {
                val policyName = clients.joinToString(",")
                val policy: Policy.Builder = clientToPolicyBuilder.computeIfAbsent(policyName) {
                    Policy.newBuilder().addAllPrincipals(principals)
                }

                val combinedPermissions = createCombinedPermissions(incomingEndpoint)
                policy.addPermissions(combinedPermissions)
            }
        }

        val clientToPolicy = clientToPolicyBuilder.mapValues { it.value.build() }
        return RBAC.newBuilder()
                .setAction(RBAC.Action.ALLOW)
                .putAllPolicies(clientToPolicy)
                .build()
    }

    private fun createStatusRoutePrincipal(statusRouteProperties: StatusRouteProperties): Policy.Builder? {
        return if (statusRouteProperties.enabled) {
            val permission = Permission.newBuilder().setHeader(
                    HeaderMatcher.newBuilder()
                            .setName(":path")
                            .setPrefixMatch(statusRouteProperties.pathPrefix)
                            .build())
                    .build()

            Policy.newBuilder()
                    .addPrincipals(Principal.newBuilder().setAny(true).build())
                    .addPermissions(permission)
        } else {
            null
        }
    }

    private fun createCombinedPermissions(incomingEndpoint: IncomingEndpoint): Permission.Builder {
        val combinedPermissions = Permission.newBuilder()
        val pathPermission = Permission.newBuilder()
                .setUrlPath(createPathMatcher(incomingEndpoint))

        if (incomingEndpoint.methods.isNotEmpty()) {
            val methodPermissions = createMethodPermissions(incomingEndpoint)
            combinedPermissions.setAndRules(
                    Permission.Set.newBuilder().addAllRules(listOf(
                            pathPermission.build(),
                            methodPermissions.build()
                    ))
            )
        } else {
            combinedPermissions.setAndRules(
                    Permission.Set.newBuilder().addAllRules(listOf(pathPermission.build()))
            )
        }

        return combinedPermissions
    }

    private fun createMethodPermissions(incomingEndpoint: IncomingEndpoint): Permission.Builder {
        val methodPermissions = Permission.newBuilder()
        val methodPermissionSet = Permission.Set.newBuilder()
        val methodPermissionsList = incomingEndpoint.methods.map(this::mapMethodToHeaderMatcher)
        methodPermissionSet.addAllRules(methodPermissionsList)
        methodPermissions.setOrRules(methodPermissionSet.build())

        return methodPermissions
    }

    private fun resolveClients(incomingEndpoint: IncomingEndpoint, roles: List<Role>): List<String> {
        val clients = incomingEndpoint.clients.flatMap { clientOrRole ->
            roles.find { it.name == clientOrRole }?.clients ?: setOf(clientOrRole)
        }
        return clients.sorted()
    }

    private fun mapMethodToHeaderMatcher(method: String): Permission {
        val methodMatch = HeaderMatcher.newBuilder().setName(":method").setExactMatch(method).build()
        return Permission.newBuilder().setHeader(methodMatch).build()
    }

    private fun mapClientToPrincipals(client: String, snapshot: GlobalSnapshot): List<Principal> {
        val (decomposed, selector, matching) = decompose(client)
        val staticRangesForClient = staticIpRange(decomposed, selector, matching)

        return if (decomposed in incomingServicesSourceAuthentication) {
            ipFromDiscoveryPrincipals(decomposed, selector, matching, snapshot)
        } else if (staticRangesForClient != null) {
            staticRangesForClient
        } else {
            headerPrincipals(client)
        }
    }

    private fun staticIpRange(decomposed: String, selector: Selector?, matching: Matching?): List<Principal>? {
        val range = staticIpRanges[decomposed]
        return if (selector != null && matching != null && range != null) {
            addAdditionalAuthentication(selector, matching, range.map { it.toBuilder() })
        } else {
            staticIpRanges[decomposed]
        }
    }

    private fun createStaticIpRanges(): Map<String, List<Principal>> {
        val ranges = incomingPermissionsProperties.sourceIpAuthentication.ipFromRange

        return ranges.mapValues {
            it.value.map { ipWithPrefix ->
                val (ip, prefixLength) = ipWithPrefix.split("/")

                Principal.newBuilder().setSourceIp(CidrRange.newBuilder()
                        .setAddressPrefix(ip)
                        .setPrefixLen(UInt32Value.of(prefixLength.toInt())).build())
                        .build()
            }
        }
    }

    private fun ipFromDiscoveryPrincipals(
        decomposed: String,
        selector: Selector?,
        matching: Matching?,
        snapshot: GlobalSnapshot
    ): List<Principal> {
        val clientEndpoints = snapshot.endpoints.resources().filterKeys { decomposed == it }.values
        return clientEndpoints.flatMap { clusterLoadAssignment ->
            clusterLoadAssignment.endpointsList.flatMap { lbEndpoints ->
                lbEndpoints.lbEndpointsList.map { lbEndpoint ->
                    lbEndpoint.endpoint.address
                }
            }
        }.flatMap { address ->
            val sourceIpPrincipal = Principal.newBuilder()
                    .setSourceIp(CidrRange.newBuilder()
                    .setAddressPrefix(address.socketAddress.address)
                    .setPrefixLen(EXACT_IP_MASK).build())

            if (selector != null && matching != null) {
                addAdditionalAuthentication(selector, matching, listOf(sourceIpPrincipal))
            } else {
                listOf(sourceIpPrincipal.build())
            }
        }
    }

    private fun addAdditionalAuthentication(
        selector: Selector,
        matching: Matching,
        sourceIpPrincipals: List<Principal.Builder>
    ): List<Principal> {
        val andPrincipal = Principal.newBuilder()
        val (matchingType, matchingValue) = matching

        val additionalAuthenticationPrincipal = when (matchingType) {
            AdditionalAuthenticationMethod.HEADER -> Principal.newBuilder().setHeader(
                    HeaderMatcher.newBuilder()
                            .setName(matchingValue)
                            .setExactMatch(selector)
            )
        }

        return sourceIpPrincipals.map {
            val principalSet = Principal.Set.newBuilder()
                    .addAllIds(listOf(it.build(), additionalAuthenticationPrincipal.build()))
                    .build()

            andPrincipal.setAndIds(principalSet).build()
        }
    }

    private fun decompose(client: String): Triple<String, Selector?, Matching?> {
        val parts = client.split(":", ignoreCase = false, limit = 2)
        return if (isNotCompositeClient(parts)) {
            Triple(client, null, null)
        } else {
            val decomposedClient = parts[0]
            val matching = incomingPermissionsProperties.selectorMatching[decomposedClient]
            if (matching == null) {
                logger.info("No selector matching found for client $decomposedClient in EC properties. " +
                        "Source IP based authentication will not contain additional matching authentication.")
                return Triple(client, null, null)
            }

            val selector: Selector = parts[1]

            Triple(decomposedClient, selector, matching)
        }
    }

    private fun isNotCompositeClient(parts: List<String>) = parts.size != 2

    private fun headerPrincipals(client: String): List<Principal> {
        val clientMatch = HeaderMatcher.newBuilder()
                .setName(incomingPermissionsProperties.clientIdentityHeader).setExactMatch(client).build()

        return listOf(Principal.newBuilder().setHeader(clientMatch).build())
    }

    private fun createPathMatcher(incomingEndpoint: IncomingEndpoint): PathMatcher {
        return when (incomingEndpoint.pathMatchingType) {
            PathMatchingType.PATH ->
                PathMatcher.newBuilder()
                        .setPath(
                                StringMatcher.newBuilder()
                                        .setExact(incomingEndpoint.path)
                                        .build()
                        )
                        .build()

            PathMatchingType.PATH_PREFIX ->
                PathMatcher.newBuilder()
                        .setPath(
                                StringMatcher.newBuilder()
                                        .setPrefix(incomingEndpoint.path)
                                        .build()
                        )
                        .build()
        }
    }

    fun createHttpFilter(group: Group, snapshot: GlobalSnapshot): HttpFilter? {
        return if (incomingPermissionsProperties.enabled && group.proxySettings.incoming.permissionsEnabled) {
            val rules = getRules(group.serviceName, group.proxySettings.incoming, snapshot)
            val rbacFilter = RBACFilter.newBuilder().setRules(rules).build()
            HttpFilter.newBuilder().setName("envoy.filters.http.rbac").setTypedConfig(Any.pack(rbacFilter)).build()
        } else {
            null
        }
    }
}
