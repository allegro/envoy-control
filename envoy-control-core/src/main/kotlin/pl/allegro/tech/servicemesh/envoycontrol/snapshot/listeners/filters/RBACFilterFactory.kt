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
import pl.allegro.tech.servicemesh.envoycontrol.groups.Client
import pl.allegro.tech.servicemesh.envoycontrol.groups.ClientMatching
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group
import pl.allegro.tech.servicemesh.envoycontrol.groups.Incoming
import pl.allegro.tech.servicemesh.envoycontrol.groups.IncomingEndpoint
import pl.allegro.tech.servicemesh.envoycontrol.groups.PathMatchingType
import pl.allegro.tech.servicemesh.envoycontrol.groups.Role
import pl.allegro.tech.servicemesh.envoycontrol.groups.Selector
import pl.allegro.tech.servicemesh.envoycontrol.logger
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.GlobalSnapshot
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.IncomingPermissionsProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SelectorMatching
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.StatusRouteProperties
import io.envoyproxy.envoy.config.filter.http.rbac.v2.RBAC as RBACFilter

class RBACFilterFactory(
    private val incomingPermissionsProperties: IncomingPermissionsProperties,
    statusRouteProperties: StatusRouteProperties
) {
    private val incomingServicesSourceAuthentication = incomingPermissionsProperties
            .sourceIpAuthentication
            .ipFromServiceDiscovery
            .enabledForIncomingServices

    private val incomingServicesIpRangeAuthentication = incomingPermissionsProperties
            .sourceIpAuthentication
            .ipFromRange
            .keys

    init {
        incomingPermissionsProperties.selectorMatching.forEach {
            if (it.key !in incomingServicesIpRangeAuthentication && it.key !in incomingServicesSourceAuthentication) {
                throw IllegalArgumentException("${it.key} is not defined in ip range or ip from discovery section.")
            }
        }
    }

    companion object {
        private val logger by logger()
        private const val ANY_PRINCIPAL_NAME = "_ANY_"
        private val EXACT_IP_MASK = UInt32Value.of(32)
    }

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

            val clientMatchingList = resolveClients(incomingEndpoint, incomingPermissions.roles)
            val principals = clientMatchingList.flatMap { mapClientMatchingToPrincipals(it, snapshot) }
            if (principals.isNotEmpty()) {
                val policyName = clientMatchingList.joinToString(",") { it.compositeName() }
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

    private fun resolveClients(
        incomingEndpoint: IncomingEndpoint,
        roles: List<Role>
    ): Collection<ClientMatching> {
        val clients = incomingEndpoint.clients.flatMap { clientOrRole ->
            roles.find { it.name == clientOrRole.name }?.clients?.map { it } ?: setOf(clientOrRole)
        }
        // sorted order ensures that we do not duplicate rules
        return clients.toSortedSet(Comparator.comparing<ClientMatching, String> { it.name })
    }

    private fun mapMethodToHeaderMatcher(method: String): Permission {
        val methodMatch = HeaderMatcher.newBuilder().setName(":method").setExactMatch(method).build()
        return Permission.newBuilder().setHeader(methodMatch).build()
    }

    private fun mapClientMatchingToPrincipals(
        clientMatching: ClientMatching,
        snapshot: GlobalSnapshot
    ): List<Principal> {
        val staticRangesForClient = staticIpRange(clientMatching)

        return if (clientMatching.name in incomingServicesSourceAuthentication) {
            ipFromDiscoveryPrincipals(clientMatching, snapshot)
        } else if (staticRangesForClient != null) {
            staticRangesForClient
        } else {
            headerPrincipals(clientMatching.name)
        }
    }

    private fun staticIpRange(clientMatching: ClientMatching): List<Principal>? {
        val range = staticIpRanges[clientMatching.name]
        return if (clientMatching.selector != null && clientMatching.selectorMatching != null && range != null) {
            addAdditionalMatching(clientMatching.selector, clientMatching.selectorMatching, range)
        } else {
            range
        }
    }

    private fun createStaticIpRanges(): Map<Client, List<Principal>> {
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
        clientMatching: ClientMatching,
        snapshot: GlobalSnapshot
    ): List<Principal> {
        val clientEndpoints = snapshot.endpoints.resources().filterKeys { clientMatching.name == it }.values
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
                    .build()

            if (clientMatching.selector != null && clientMatching.selectorMatching != null) {
                addAdditionalMatching(
                        clientMatching.selector,
                        clientMatching.selectorMatching,
                        listOf(sourceIpPrincipal)
                )
            } else {
                listOf(sourceIpPrincipal)
            }
        }
    }

    private fun addAdditionalMatching(
        selector: Selector,
        selectorMatching: SelectorMatching,
        sourceIpPrincipals: List<Principal>
    ): List<Principal> {
        return if (selectorMatching.header.isNotEmpty()) {
            val orPrincipal = Principal.newBuilder()
                    .setOrIds(Principal.Set.newBuilder().addAllIds(sourceIpPrincipals))
                    .build()

            val additionalMatchingPrincipal = Principal.newBuilder()
                    .setHeader(HeaderMatcher.newBuilder().setName(selectorMatching.header).setExactMatch(selector))
                    .build()

            listOf(Principal.newBuilder().setAndIds(Principal.Set.newBuilder().addAllIds(
                    listOf(orPrincipal, additionalMatchingPrincipal)
            )).build())
        } else {
            sourceIpPrincipals
        }
    }

    private fun headerPrincipals(client: Client): List<Principal> {
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
