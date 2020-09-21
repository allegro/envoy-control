package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters

import com.google.protobuf.Any
import com.google.protobuf.UInt32Value
import io.envoyproxy.envoy.config.core.v3.CidrRange
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment
import io.envoyproxy.envoy.config.rbac.v3.Policy
import io.envoyproxy.envoy.config.rbac.v3.Principal
import io.envoyproxy.envoy.config.rbac.v3.RBAC
import io.envoyproxy.envoy.config.route.v3.HeaderMatcher
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter
import io.envoyproxy.envoy.type.matcher.v3.StringMatcher
import pl.allegro.tech.servicemesh.envoycontrol.groups.ClientWithSelector
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group
import pl.allegro.tech.servicemesh.envoycontrol.groups.Incoming
import pl.allegro.tech.servicemesh.envoycontrol.groups.IncomingEndpoint
import pl.allegro.tech.servicemesh.envoycontrol.groups.Role
import pl.allegro.tech.servicemesh.envoycontrol.logger
import pl.allegro.tech.servicemesh.envoycontrol.protocol.TlsUtils
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.Client
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.GlobalSnapshot
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.IncomingPermissionsProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SelectorMatching
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.StatusRouteProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.TlsAuthenticationProperties
import io.envoyproxy.envoy.extensions.filters.http.rbac.v3.RBAC as RBACFilter

class RBACFilterFactory(
    private val incomingPermissionsProperties: IncomingPermissionsProperties,
    statusRouteProperties: StatusRouteProperties,
    private val rBACFilterPermissions: RBACFilterPermissions = RBACFilterPermissions()
) {
    private val incomingServicesSourceAuthentication = incomingPermissionsProperties
            .sourceIpAuthentication
            .ipFromServiceDiscovery
            .enabledForIncomingServices

    private val incomingServicesIpRangeAuthentication = incomingPermissionsProperties
            .sourceIpAuthentication
            .ipFromRange
            .keys

    private val anyPrincipal = Principal.newBuilder().setAny(true).build()
    private val denyForAllPrincipal = Principal.newBuilder().setNotId(anyPrincipal).build()

    init {
        incomingPermissionsProperties.selectorMatching.forEach {
            if (it.key !in incomingServicesIpRangeAuthentication && it.key !in incomingServicesSourceAuthentication) {
                throw IllegalArgumentException("${it.key} is not defined in ip range or ip from discovery section.")
            }
        }
    }

    companion object {
        private val logger by logger()
        private const val ALLOW_UNLISTED_POLICY_NAME = "ALLOW_UNLISTED_POLICY"
        private const val STATUS_ROUTE_POLICY_NAME = "STATUS_ALLOW_ALL_POLICY"
        private val EXACT_IP_MASK = UInt32Value.of(32)
    }

    private val statusRoutePolicy = createStatusRoutePolicy(statusRouteProperties)
    private val staticIpRanges = createStaticIpRanges()

    data class EndpointWithPolicy(val endpoint: IncomingEndpoint, val policy: Policy.Builder)

    private fun getIncomingEndpointPolicies(
        serviceName: String,
        incomingPermissions: Incoming,
        snapshot: GlobalSnapshot,
        roles: List<Role>
    ): List<EndpointWithPolicy> {
        val principalCache = mutableMapOf<ClientWithSelector, List<Principal>>()
        return incomingPermissions.endpoints.map { incomingEndpoint ->
            if (incomingEndpoint.clients.isEmpty()) {
                logger.warn("An incoming endpoint definition for $serviceName does not have any clients defined." +
                    "It means that no one will be able to contact that endpoint, unless 'log' policy is defined " +
                    "for unlisted endpoints or clients.")
            }

            val clientsWithSelectors = resolveClientsWithSelectors(incomingEndpoint, roles)
            val principals = clientsWithSelectors
                .flatMap { client ->
                    principalCache.computeIfAbsent(client) { mapClientWithSelectorToPrincipals(it, snapshot) }
                }.toSet()
                .ifEmpty { setOf(denyForAllPrincipal) }

            val policy = Policy.newBuilder().addAllPrincipals(principals)
            val combinedPermissions = rBACFilterPermissions.createCombinedPermissions(incomingEndpoint)
            policy.addPermissions(combinedPermissions)
            EndpointWithPolicy(incomingEndpoint, policy)
        }
    }

    data class Rules(val shadowRules: RBAC.Builder, val actualRules: RBAC.Builder)

    private fun getRules(
        serviceName: String,
        incomingPermissions: Incoming,
        snapshot: GlobalSnapshot,
        roles: List<Role>
    ): Rules {

        val incomingEndpointsPolicies = getIncomingEndpointPolicies(
                serviceName, incomingPermissions, snapshot, roles
        )

        val restrictedEndpointsPolicies = incomingEndpointsPolicies.asSequence()
            .filter { it.endpoint.unlistedClientsPolicy == Incoming.UnlistedPolicy.BLOCKANDLOG }
            .map { (endpoint, policy) -> "$endpoint" to policy.build() }.toMap()

        val loggedEndpointsPolicies = incomingEndpointsPolicies.asSequence()
            .filter { it.endpoint.unlistedClientsPolicy == Incoming.UnlistedPolicy.LOG }
            .map { (endpoint, policy) -> "$endpoint" to policy.build() }.toMap()

        val commonPolicies = statusRoutePolicy + restrictedEndpointsPolicies

        val allowUnlistedPolicies = unlistedAndLoggedEndpointsPolicies(
            incomingPermissions,
            commonPolicies.values,
            loggedEndpointsPolicies.values
        )

        val shadowPolicies = commonPolicies + loggedEndpointsPolicies
        val actualPolicies = commonPolicies + allowUnlistedPolicies

        val actualRules = RBAC.newBuilder()
            .setAction(RBAC.Action.ALLOW)
            .putAllPolicies(actualPolicies)

        val shadowRules = RBAC.newBuilder()
                .setAction(RBAC.Action.ALLOW)
                .putAllPolicies(shadowPolicies)

        return Rules(shadowRules = shadowRules, actualRules = actualRules)
    }

    private fun unlistedAndLoggedEndpointsPolicies(
        incomingPermissions: Incoming,
        restrictedEndpointsPolicies: Iterable<Policy>,
        loggedEndpointsPolicies: Iterable<Policy>
    ): Map<String, Policy> {
        return if (incomingPermissions.unlistedEndpointsPolicy == Incoming.UnlistedPolicy.LOG) {
            allowUnlistedEndpointsPolicy(restrictedEndpointsPolicies)
        } else {
            allowLoggedEndpointsPolicy(loggedEndpointsPolicies)
        }
    }

    private fun allowUnlistedEndpointsPolicy(allowedEndpointsPolicies: Iterable<Policy>): Map<String, Policy> {

        val allDefinedEndpointsPermissions = allowedEndpointsPolicies.asSequence()
            .flatMap { it.permissionsList.asSequence() }
            .toList()

        return mapOf(ALLOW_UNLISTED_POLICY_NAME to Policy.newBuilder()
            .addPrincipals(anyPrincipal)
            .addPermissions(noneOf(allDefinedEndpointsPermissions))
            .build()
        )
    }

    private fun allowLoggedEndpointsPolicy(loggedEndpointsPolicies: Iterable<Policy>): Map<String, Policy> {

        val allLoggedEndpointsPermissions = loggedEndpointsPolicies.asSequence()
            .flatMap { it.permissionsList.asSequence() }
            .toList()

        if (allLoggedEndpointsPermissions.isEmpty()) {
            return mapOf()
        }

        return mapOf(ALLOW_UNLISTED_POLICY_NAME to Policy.newBuilder()
            .addPrincipals(anyPrincipal)
            .addPermissions(anyOf(allLoggedEndpointsPermissions))
            .build()
        )
    }

    private fun createStatusRoutePolicy(statusRouteProperties: StatusRouteProperties): Map<String, Policy> {
        return if (statusRouteProperties.enabled) {
            val permissions = statusRouteProperties.endpoints
                .map {
                    rBACFilterPermissions.createPathPermission(
                        path = it.path,
                        matchingType = it.matchingType
                    ).build()
                }
            val policy = Policy.newBuilder()
                .addPrincipals(anyPrincipal)
                .addPermissions(anyOf(permissions))
                .build()
            mapOf(STATUS_ROUTE_POLICY_NAME to policy)
        } else {
            mapOf()
        }
    }

    private fun resolveClientsWithSelectors(
        incomingEndpoint: IncomingEndpoint,
        roles: List<Role>
    ): Collection<ClientWithSelector> {
        val clients = incomingEndpoint.clients.flatMap { clientOrRole ->
            roles.find { it.name == clientOrRole.name }?.clients ?: setOf(clientOrRole)
        }
        // sorted order ensures that we do not duplicate rules
        return clients.toSortedSet()
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
        } else {
            tlsPrincipals(incomingPermissionsProperties.tlsAuthentication, clientWithSelector.name)
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

    fun createHttpFilter(group: Group, snapshot: GlobalSnapshot): HttpFilter? {
        return if (incomingPermissionsProperties.enabled && group.proxySettings.incoming.permissionsEnabled) {
            val rules = getRules(
                group.serviceName,
                group.proxySettings.incoming,
                snapshot,
                group.proxySettings.incoming.roles
            )

            val rbacFilter = RBACFilter.newBuilder()
                .setRules(rules.actualRules)
                .setShadowRules(rules.shadowRules)
                .build()

            HttpFilter.newBuilder().setName("envoy.filters.http.rbac")
                    .setTypedConfig(Any.pack(rbacFilter)).build()
        } else {
            null
        }
    }
}
