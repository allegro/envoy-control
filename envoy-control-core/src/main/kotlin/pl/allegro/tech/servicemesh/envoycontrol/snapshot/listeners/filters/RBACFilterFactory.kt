package pl.allegro.tech.servicemesh.envoycontrol.snapshot.listeners.filters

import com.google.protobuf.Any
import com.google.protobuf.UInt32Value
import io.envoyproxy.envoy.api.v2.ClusterLoadAssignment
import io.envoyproxy.envoy.api.v2.core.CidrRange
import io.envoyproxy.envoy.api.v2.route.HeaderMatcher
import io.envoyproxy.envoy.config.filter.network.http_connection_manager.v2.HttpFilter
import io.envoyproxy.envoy.config.rbac.v2.Permission
import io.envoyproxy.envoy.config.rbac.v2.Policy
import io.envoyproxy.envoy.config.rbac.v2.Principal
import io.envoyproxy.envoy.config.rbac.v2.RBAC
import io.envoyproxy.envoy.type.matcher.PathMatcher
import io.envoyproxy.envoy.type.matcher.StringMatcher
import pl.allegro.tech.servicemesh.envoycontrol.groups.ClientWithSelector
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group
import pl.allegro.tech.servicemesh.envoycontrol.groups.Incoming
import pl.allegro.tech.servicemesh.envoycontrol.groups.IncomingEndpoint
import pl.allegro.tech.servicemesh.envoycontrol.groups.PathMatchingType
import pl.allegro.tech.servicemesh.envoycontrol.groups.Role
import pl.allegro.tech.servicemesh.envoycontrol.logger
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.Client
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.GlobalSnapshot
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.IncomingPermissionsProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SelectorMatching
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.StatusRouteProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.TlsAuthenticationProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.TlsUtils
import io.envoyproxy.envoy.config.filter.http.rbac.v2.RBAC as RBACFilter

@Suppress("LargeClass") // TODO: https://github.com/allegro/envoy-control/issues/121
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

    private fun getRules(
            serviceName: String,
            incomingEndpoints: List<IncomingEndpoint>,
            snapshot: GlobalSnapshot,
            roles: List<Role>
    ): RBAC.Builder {
        val clientToPolicyBuilder = mutableMapOf<String, Policy.Builder>()

        if (statusRoutePrincipal != null) {
            clientToPolicyBuilder[ANY_PRINCIPAL_NAME] = statusRoutePrincipal
        }

        incomingEndpoints.forEach { incomingEndpoint ->
            if (incomingEndpoint.clients.isEmpty()) {
                logger.warn("An incoming endpoint definition for $serviceName does not have any clients defined." +
                        "It means that no one will be able to contact that endpoint.")
                return@forEach
            }

            val clientsWithSelectors = resolveClientsWithSelectors(incomingEndpoint, roles)
            val principals = clientsWithSelectors.flatMap { mapClientWithSelectorToPrincipals(it, snapshot) }.toSet()

            if (principals.isNotEmpty()) {
                val policyName = clientsWithSelectors.joinToString(",") { it.compositeName() }
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
    }

    private fun getAllRules(
            serviceName: String,
            incomingPermissions: Incoming,
            snapshot: GlobalSnapshot,
            roles: List<Role>
    ): Pair<RBAC.Builder, RBAC.Builder> {
        val shouldBlock = incomingPermissions.endpoints.filter {
            if (it.unlistedClientsPolicy != null) {
                if (it.unlistedClientsPolicy == IncomingEndpoint.UnlistedClientsPolicy.BLOCK) {
                    return@filter true
                } else {
                    return@filter false
                }
            } else {
                if (incomingPermissions.unlistedEndpointsPolicy != null) {
                    if (incomingPermissions.unlistedEndpointsPolicy == Incoming.UnlistedEndpointsPolicy.BLOCK) {
                        return@filter true
                    } else {
                        return@filter false
                    }
                } else {
                    // TODO: decide what if someone does not provide unlistedEndpointsPolicy
                    return@filter false
                }
            }
        }

        val blockRules = getRules(serviceName, shouldBlock, snapshot, roles)

        // TODO: figure out if this can be the default
        val actualRules = if (incomingPermissions.unlistedEndpointsPolicy == Incoming.UnlistedEndpointsPolicy.LOG) {
            val otherRules = notRule(blockRules)
            otherRules.mergeFrom(blockRules.build())
        } else {
            getRules(serviceName, shouldBlock, snapshot, roles)
        }

        val shadowRules = getRules(serviceName, incomingPermissions.endpoints, snapshot, roles)

        return Pair(actualRules, shadowRules)
    }

    private fun notRule(rules: RBAC.Builder): RBAC.Builder {
        val notRule = RBAC.newBuilder()

        val policyMap = rules.policiesMap.mapValues { policy ->
            val permissions = policy.value.permissionsList.map { permission ->
                Permission.newBuilder().setNotRule(permission).build()
            }

            val principals = policy.value.principalsList.map { principal ->
                Principal.newBuilder().setNotId(principal).build()
            }

            Policy.newBuilder()
                    .addAllPermissions(permissions)
                    .addAllPrincipals(principals)
                    .build()
        }.mapKeys {
            it.key + "_not"
        }

        return notRule
                .setAction(RBAC.Action.ALLOW)
                .putAllPolicies(policyMap)
    }

    private fun getShadowRules(serviceName: String, incomingPermissions: Incoming, snapshot: GlobalSnapshot): RBAC {
        var counter = 0
        val builder = RBAC.newBuilder()

        incomingPermissions.endpoints
                .forEach { incomingEndpoint ->
                    val shouldAllow =
                        (incomingPermissions.unlistedEndpointsPolicy != null && incomingPermissions.unlistedEndpointsPolicy.equals(Incoming.UnlistedEndpointsPolicy.LOG) ||
                                incomingEndpoint.unlistedClientsPolicy != null && incomingEndpoint.unlistedClientsPolicy.equals(IncomingEndpoint.UnlistedClientsPolicy.LOG))

                    val shouldBlock =
                        (incomingPermissions.unlistedEndpointsPolicy != null && incomingPermissions.unlistedEndpointsPolicy.equals(Incoming.UnlistedEndpointsPolicy.BLOCK)) ||
                                incomingEndpoint.unlistedClientsPolicy != null && incomingEndpoint.unlistedClientsPolicy.equals(IncomingEndpoint.UnlistedClientsPolicy.BLOCK)

                    val clientToPolicyBuilder = mutableMapOf<String, Policy.Builder>()
                    if (shouldAllow) {
                        clientToPolicyBuilder.computeIfAbsent(String.format("_ANY%d_", counter++)) {
                            Policy.newBuilder().addPermissions(createCombinedPermissions(incomingEndpoint))
                                    .addAllPrincipals(listOf(Principal.newBuilder().setAny(true).build()))
                        }

                        val clientToPolicy = clientToPolicyBuilder.mapValues { it.value.build() }
                        val allow = RBAC.newBuilder()
                                .setAction(RBAC.Action.ALLOW)
                                .putAllPolicies(clientToPolicy)
                        builder.mergeFrom(allow.build())
                    } else if (shouldBlock) {

                        clientToPolicyBuilder.computeIfAbsent(String.format("_ANY%d_", counter++)) {
                            Policy.newBuilder().addPermissions(createCombinedPermissions(incomingEndpoint))
                                    .addAllPrincipals(listOf(Principal.newBuilder().setAny(true).build()))
                        }

                        val clientToPolicy = clientToPolicyBuilder.mapValues { it.value.build() }
                        val block = RBAC.newBuilder()
                                .setAction(RBAC.Action.DENY)
                                .putAllPolicies(clientToPolicy)
                        builder.mergeFrom(block.build())
                    }
                }

        return builder.build()
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

    private fun resolveClientsWithSelectors(
        incomingEndpoint: IncomingEndpoint,
        roles: List<Role>
    ): Collection<ClientWithSelector> {
        val clients = incomingEndpoint.clients.flatMap { clientOrRole ->
            roles.find { it.name == clientOrRole.name }?.clients ?: setOf(clientOrRole)
        }.toSortedSet()
        // sorted order ensures that we do not duplicate rules
        return clients
    }

    private fun mapMethodToHeaderMatcher(method: String): Permission {
        val methodMatch = HeaderMatcher.newBuilder().setName(":method").setExactMatch(method).build()
        return Permission.newBuilder().setHeader(methodMatch).build()
    }

    private fun mapClientWithSelectorToPrincipals(
        clientWithSelector: ClientWithSelector,
        snapshot: GlobalSnapshot
    ): List<Principal> {
        val selectorMatching = getSelectorMatching(clientWithSelector, incomingPermissionsProperties)
        val staticRangesForClient = staticIpRange(clientWithSelector, selectorMatching)

        return if (clientWithSelector.name in incomingServicesSourceAuthentication) {
            ipFromDiscoveryPrincipals(clientWithSelector, selectorMatching, snapshot)
        } else if (staticRangesForClient != null) {
            listOf(staticRangesForClient)
        } else if (snapshot.mtlsEnabledForCluster(clientWithSelector.name)) {
            tlsPrincipals(incomingPermissionsProperties.tlsAuthentication, clientWithSelector.name)
        } else {
            headerPrincipals(clientWithSelector.name) // TODO(https://github.com/allegro/envoy-control/issues/122)
            // remove when service name is passed from certificate
        }
    }

    private fun getSelectorMatching(
        client: ClientWithSelector,
        incomingPermissionsProperties: IncomingPermissionsProperties
    ): SelectorMatching? {
        val matching = incomingPermissionsProperties.selectorMatching[client.name]
        if (matching == null && client.selector != null) {
            logger.warn("No selector matching found for client '${client.name}' with selector '${client.selector}' " +
                    "in EC properties. Source IP based authentication will not contain additional matching.")
            return null
        }

        return matching
    }

    private fun staticIpRange(client: ClientWithSelector, selectorMatching: SelectorMatching?): Principal? {
        val ranges = staticIpRanges[client.name]
        return if (client.selector != null && selectorMatching != null && ranges != null) {
            addAdditionalMatching(client.selector, selectorMatching, ranges)
        } else {
            ranges
        }
    }

    private fun tlsPrincipals(tlsProperties: TlsAuthenticationProperties, client: String): List<Principal> {
        val principalName = TlsUtils.resolveSanUri(client, tlsProperties.sanUriFormat)
        return listOf(Principal.newBuilder().setAuthenticated(
                Principal.Authenticated.newBuilder()
                        .setPrincipalName(StringMatcher.newBuilder()
                                .setExact(principalName)
                                .build()
                        )
                ).build()
        )
    }

    private fun createStaticIpRanges(): Map<Client, Principal> {
        val ranges = incomingPermissionsProperties.sourceIpAuthentication.ipFromRange

        return ranges.mapValues {
            val principals = it.value.map { ipWithPrefix ->
                val (ip, prefixLength) = ipWithPrefix.split("/")

                Principal.newBuilder().setSourceIp(CidrRange.newBuilder()
                        .setAddressPrefix(ip)
                        .setPrefixLen(UInt32Value.of(prefixLength.toInt())).build())
                        .build()
            }

            Principal.newBuilder().setOrIds(Principal.Set.newBuilder().addAllIds(principals).build()).build()
        }
    }

    private fun ipFromDiscoveryPrincipals(
        client: ClientWithSelector,
        selectorMatching: SelectorMatching?,
        snapshot: GlobalSnapshot
    ): List<Principal> {
        val clusterLoadAssignment = snapshot.endpoints.resources()[client.name]
        val sourceIpPrincipal = mapEndpointsToExactPrincipals(clusterLoadAssignment)

        return if (sourceIpPrincipal == null) {
            listOf()
        } else if (client.selector != null && selectorMatching != null) {
            listOf(addAdditionalMatching(client.selector, selectorMatching, sourceIpPrincipal))
        } else {
            listOf(sourceIpPrincipal)
        }
    }

    private fun mapEndpointsToExactPrincipals(clusterLoadAssignment: ClusterLoadAssignment?): Principal? {
        val principals = clusterLoadAssignment?.endpointsList?.flatMap { lbEndpoints ->
            lbEndpoints.lbEndpointsList.map { lbEndpoint ->
                lbEndpoint.endpoint.address
            }
        }.orEmpty().map { address ->
            Principal.newBuilder()
                    .setSourceIp(CidrRange.newBuilder()
                            .setAddressPrefix(address.socketAddress.address)
                            .setPrefixLen(EXACT_IP_MASK).build())
                    .build()
        }

        return if (principals.isNotEmpty()) {
            Principal.newBuilder().setOrIds(Principal.Set.newBuilder().addAllIds(principals).build()).build()
        } else {
            null
        }
    }

    private fun addAdditionalMatching(
        selector: String,
        selectorMatching: SelectorMatching,
        sourceIpPrincipal: Principal
    ): Principal {
        return if (selectorMatching.header.isNotEmpty()) {
            val additionalMatchingPrincipal = Principal.newBuilder()
                    .setHeader(HeaderMatcher.newBuilder().setName(selectorMatching.header).setExactMatch(selector))
                    .build()

            Principal.newBuilder().setAndIds(Principal.Set.newBuilder().addAllIds(
                    listOf(sourceIpPrincipal, additionalMatchingPrincipal)
            ).build()).build()
        } else {
            sourceIpPrincipal
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
            val (actualRules, shadowRules) = getAllRules(
                    group.serviceName,
                    group.proxySettings.incoming,
                    snapshot,
                    group.proxySettings.incoming.roles
            )
            val rbacFilter = RBACFilter.newBuilder().setRules(actualRules).setShadowRules(shadowRules)
            HttpFilter.newBuilder().setName("envoy.filters.http.rbac")
                    .setTypedConfig(Any.pack(rbacFilter.build())).build()
        } else {
            null
        }
    }
}
