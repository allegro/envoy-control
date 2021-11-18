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
import io.envoyproxy.envoy.type.matcher.v3.ListMatcher
import io.envoyproxy.envoy.type.matcher.v3.MetadataMatcher
import io.envoyproxy.envoy.type.matcher.v3.StringMatcher
import io.envoyproxy.envoy.type.matcher.v3.ValueMatcher
import pl.allegro.tech.servicemesh.envoycontrol.groups.ClientWithSelector
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group
import pl.allegro.tech.servicemesh.envoycontrol.groups.Incoming
import pl.allegro.tech.servicemesh.envoycontrol.groups.IncomingEndpoint
import pl.allegro.tech.servicemesh.envoycontrol.groups.OAuth
import pl.allegro.tech.servicemesh.envoycontrol.groups.Role
import pl.allegro.tech.servicemesh.envoycontrol.logger
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.Client
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.GlobalSnapshot
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.IncomingPermissionsProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.JwtFilterProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.OAuthProvider
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SelectorMatching
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.StatusRouteProperties
import io.envoyproxy.envoy.extensions.filters.http.rbac.v3.RBAC as RBACFilter

class RBACFilterFactory(
    private val incomingPermissionsProperties: IncomingPermissionsProperties,
    statusRouteProperties: StatusRouteProperties,
    private val rBACFilterPermissions: RBACFilterPermissions = RBACFilterPermissions(),
    private val jwtProperties: JwtFilterProperties = JwtFilterProperties()
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
    private val fullAccessClients = incomingPermissionsProperties.clientsAllowedToAllEndpoints.map {
        ClientWithSelector(name = it)
    }
    private val sanUriMatcherFactory = SanUriMatcherFactory(incomingPermissionsProperties.tlsAuthentication)

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
        private const val ALLOW_LOGGED_POLICY_NAME = "ALLOW_LOGGED_POLICY"
        private const val STATUS_ROUTE_POLICY_NAME = "STATUS_ALLOW_ALL_POLICY"
        private val EXACT_IP_MASK = UInt32Value.of(32)
    }

    private val statusRoutePolicy = createStatusRoutePolicy(statusRouteProperties)
    private val staticIpRanges = createStaticIpRanges()

    data class EndpointWithPolicy(val endpoint: IncomingEndpoint, val policy: Policy.Builder)

    private val oAuthMatchingsClients: List<Client> = jwtProperties.providers.values.flatMap { it.matchings.keys }

    private fun getIncomingEndpointPolicies(
        incomingPermissions: Incoming,
        snapshot: GlobalSnapshot,
        roles: List<Role>
    ): List<EndpointWithPolicy> {
        val principalCache = mutableMapOf<ClientWithSelector, List<Principal>>()
        return incomingPermissions.endpoints.map { incomingEndpoint ->
            val clientsWithSelectors = resolveClientsWithSelectors(incomingEndpoint, roles)

            val principals = clientsWithSelectors
                .flatMap { client ->
                    getPrincipals(
                        principalCache,
                        client,
                        snapshot,
                        incomingEndpoint.unlistedClientsPolicy,
                        incomingEndpoint.oauth
                    ).map { mergeWithOAuthPolicy(client, it, incomingEndpoint.oauth?.policy) }
                }
                .toSet()
                .ifEmpty {
                    setOf(
                        oAuthPolicyForEmptyClients(
                            incomingEndpoint.oauth?.policy,
                            incomingEndpoint.unlistedClientsPolicy
                        )
                    )
                }

            val policy = Policy.newBuilder().addAllPrincipals(principals)
            val combinedPermissions = rBACFilterPermissions.createCombinedPermissions(incomingEndpoint)
            policy.addPermissions(combinedPermissions)
            EndpointWithPolicy(incomingEndpoint, policy)
        }
    }

    private fun getPrincipals(
        principalCache: MutableMap<ClientWithSelector, List<Principal>>,
        client: ClientWithSelector,
        snapshot: GlobalSnapshot,
        unlistedClientsPolicy: Incoming.UnlistedPolicy,
        oauth: OAuth?
    ): List<Principal> {
        val principals = principalCache.computeIfAbsent(client) {
            mapClientWithSelectorToPrincipals(
                it,
                snapshot
            )
        }.toMutableList()
        principals += principalForOAuthAndLogUnlistedClients(principals, unlistedClientsPolicy, oauth)
        return principals
    }

    private fun principalForOAuthAndLogUnlistedClients(
        principals: MutableList<Principal>,
        unlistedClientsPolicy: Incoming.UnlistedPolicy,
        oauth: OAuth?
    ): List<Principal> {
        return if (principals.isNotEmpty() && unlistedClientsPolicy == Incoming.UnlistedPolicy.LOG && oauth != null) {
            listOf(anyPrincipal)
        } else {
            emptyList()
        }
    }

    data class Rules(val shadowRules: RBAC, val actualRules: RBAC)

    private fun getRules(
        incomingPermissions: Incoming,
        snapshot: GlobalSnapshot,
        roles: List<Role>
    ): Rules {

        val incomingEndpointsPolicies = getIncomingEndpointPolicies(
            incomingPermissions, snapshot, roles
        )

        val restrictedEndpointsPolicies = incomingEndpointsPolicies.asSequence()
            .filter {
                it.endpoint.unlistedClientsPolicy == Incoming.UnlistedPolicy.BLOCKANDLOG ||
                    it.endpoint.oauth?.policy != null
            }
            .map { (endpoint, policy) -> "$endpoint" to policy }.toMap()

        val loggedEndpointsPolicies = incomingEndpointsPolicies.asSequence()
            .filter {
                it.endpoint.unlistedClientsPolicy == Incoming.UnlistedPolicy.LOG && it.endpoint.oauth?.policy == null
            }
            .map { (endpoint, policy) -> "$endpoint" to policy }.toMap()

        val allowUnlistedPolicies = unlistedAndLoggedEndpointsPolicies(
            incomingPermissions,
            (statusRoutePolicy + restrictedEndpointsPolicies).values,
            loggedEndpointsPolicies.values
        )

        val shadowPolicies = (statusRoutePolicy + restrictedEndpointsPolicies + loggedEndpointsPolicies)
            .map { (endpoint, policy) -> endpoint to policy.build() }.toMap()

        val shadowRules = RBAC.newBuilder()
            .setAction(RBAC.Action.ALLOW)
            .putAllPolicies(shadowPolicies)
            .build()
        // build needs to be called before any modifications happen so we do not have to clone it

        val restrictedEndpointsPoliciesWithFullAccessClient = restrictedEndpointsPolicies.mapValues {
            addFullAccessClients(it.value)
        }
        val actualPolicies =
            (statusRoutePolicy + restrictedEndpointsPoliciesWithFullAccessClient + allowUnlistedPolicies)
                .map { (endpoint, policy) -> endpoint to policy.build() }.toMap()

        val actualRules = RBAC.newBuilder()
            .setAction(RBAC.Action.ALLOW)
            .putAllPolicies(actualPolicies)
            .build()

        return Rules(shadowRules = shadowRules, actualRules = actualRules)
    }

    private fun addFullAccessClients(policyBuilder: Policy.Builder): Policy.Builder {
        return policyBuilder.addAllPrincipals(fullAccessClients.flatMap { clientWithSelector ->
            tlsPrincipals(clientWithSelector.name)
        })
    }

    private fun unlistedAndLoggedEndpointsPolicies(
        incomingPermissions: Incoming,
        restrictedEndpointsPolicies: Collection<Policy.Builder>,
        loggedEndpointsPolicies: Collection<Policy.Builder>
    ): Map<String, Policy.Builder> {
        return if (incomingPermissions.unlistedEndpointsPolicy == Incoming.UnlistedPolicy.LOG) {
            if (incomingPermissionsProperties.overlappingPathsFix) {
                allowLoggedEndpointsPolicy(loggedEndpointsPolicies) +
                    allowUnlistedEndpointsPolicy(restrictedEndpointsPolicies)
            } else {
                allowUnlistedEndpointsPolicy(restrictedEndpointsPolicies)
            }
        } else {
            allowLoggedEndpointsPolicy(loggedEndpointsPolicies)
        }
    }

    private fun allowUnlistedEndpointsPolicy(
        allowedEndpointsPolicies: Collection<Policy.Builder>
    ): Map<String, Policy.Builder> {
        val allDefinedEndpointsPermissions = allowedEndpointsPolicies.asSequence()
            .flatMap { it.permissionsList.asSequence() }
            .toList()

        return mapOf(
            ALLOW_UNLISTED_POLICY_NAME to Policy.newBuilder()
                .addPrincipals(anyPrincipal)
                .addPermissions(noneOf(allDefinedEndpointsPermissions))
        )
    }

    private fun allowLoggedEndpointsPolicy(
        loggedEndpointsPolicies: Collection<Policy.Builder>
    ): Map<String, Policy.Builder> {
        val allLoggedEndpointsPermissions = loggedEndpointsPolicies.asSequence()
            .flatMap { it.permissionsList.asSequence() }
            .toList()

        if (allLoggedEndpointsPermissions.isEmpty()) {
            return mapOf()
        }

        return mapOf(
            ALLOW_LOGGED_POLICY_NAME to Policy.newBuilder()
                .addPrincipals(anyPrincipal)
                .addPermissions(anyOf(allLoggedEndpointsPermissions))
        )
    }

    private fun createStatusRoutePolicy(statusRouteProperties: StatusRouteProperties): Map<String, Policy.Builder> {
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
        val providerForSelector = jwtProperties.providers.values.firstOrNull {
            it.matchings.containsKey(clientWithSelector.name)
        }
        val selectorMatching = if (providerForSelector == null) {
            getSelectorMatching(clientWithSelector, incomingPermissionsProperties)
        } else {
            null
        }
        val staticRangesForClient = staticIpRange(clientWithSelector, selectorMatching)

        return if (clientWithSelector.name in incomingServicesSourceAuthentication) {
            ipFromDiscoveryPrincipals(clientWithSelector, selectorMatching, snapshot)
        } else if (staticRangesForClient != null) {
            listOf(staticRangesForClient)
        } else if (providerForSelector != null && clientWithSelector.selector != null) {
            listOf(jwtClientWithSelectorPrincipal(clientWithSelector, providerForSelector))
        } else {
            tlsPrincipals(clientWithSelector.name)
        }
    }

    private fun oAuthPolicyForEmptyClients(policy: OAuth.Policy?, unlistedPolicy: Incoming.UnlistedPolicy): Principal {
        return if (unlistedPolicy == Incoming.UnlistedPolicy.LOG) {
            when (policy) {
                OAuth.Policy.STRICT -> strictPolicyPrincipal
                OAuth.Policy.ALLOW_MISSING -> allowMissingPolicyPrincipal
                OAuth.Policy.ALLOW_MISSING_OR_FAILED -> anyPrincipal
                null -> denyForAllPrincipal
            }
        } else {
            denyForAllPrincipal
        }
    }

    private fun mergeWithOAuthPolicy(
        client: ClientWithSelector,
        principal: Principal,
        policy: OAuth.Policy?
    ): Principal {
        if (client.name in oAuthMatchingsClients) {
            return principal // don't merge if client has OAuth selector
        } else {
            return when (policy) {
                OAuth.Policy.ALLOW_MISSING -> {
                    Principal.newBuilder().setAndIds(
                        Principal.Set.newBuilder().addAllIds(
                            listOf(
                                allowMissingPolicyPrincipal,
                                principal
                            )
                        )
                    ).build()
                }
                OAuth.Policy.STRICT -> {
                    Principal.newBuilder().setAndIds(
                        Principal.Set.newBuilder().addAllIds(
                            listOf(
                                strictPolicyPrincipal,
                                principal
                            )
                        )
                    ).build()
                }
                OAuth.Policy.ALLOW_MISSING_OR_FAILED -> {
                    principal
                }
                null -> {
                    principal
                }
            }
        }
    }

    private val strictPolicyPrincipal = Principal.newBuilder().setAndIds(
        Principal.Set.newBuilder().addAllIds(
            listOf(
                Principal.newBuilder().setMetadata(
                    MetadataMatcher.newBuilder()
                        .setFilter("envoy.filters.http.header_to_metadata")
                        .addPath(
                            MetadataMatcher.PathSegment.newBuilder().setKey("jwt-status").build()
                        )
                        .setValue(
                            ValueMatcher.newBuilder().setStringMatch(StringMatcher.newBuilder().setExact("present"))
                        )
                ).build(),
                Principal.newBuilder().setMetadata(
                    MetadataMatcher.newBuilder()
                        .setFilter("envoy.filters.http.jwt_authn")
                        .addPath(
                            MetadataMatcher.PathSegment.newBuilder()
                                .setKey(jwtProperties.payloadInMetadata)
                        ).addPath(
                            MetadataMatcher.PathSegment.newBuilder()
                                .setKey(jwtProperties.fieldRequiredInToken)
                        )
                        .setValue(ValueMatcher.newBuilder().setPresentMatch(true)).build()
                ).build()
            )
        ).build()
    ).build()

    private val allowMissingPolicyPrincipal = Principal.newBuilder().setOrIds(
        Principal.Set.newBuilder().addAllIds(
            listOf(
                Principal.newBuilder()
                    .setMetadata(
                        MetadataMatcher.newBuilder()
                            .setFilter("envoy.filters.http.header_to_metadata")
                            .addPath(
                                MetadataMatcher.PathSegment.newBuilder().setKey("jwt-status").build()
                            )
                            .setValue(
                                ValueMatcher.newBuilder().setStringMatch(StringMatcher.newBuilder().setExact("missing"))
                            )
                    )
                    .build(),
                strictPolicyPrincipal
            )
        ).build()
    ).build()

    private fun jwtClientWithSelectorPrincipal(client: ClientWithSelector, oAuthProvider: OAuthProvider): Principal {
        return Principal.newBuilder().setMetadata(
            MetadataMatcher.newBuilder()
                .setFilter("envoy.filters.http.jwt_authn")
                .addPath(
                    MetadataMatcher.PathSegment.newBuilder()
                        .setKey(jwtProperties.payloadInMetadata).build()
                )
                .addPath(
                    MetadataMatcher.PathSegment.newBuilder()
                        .setKey(oAuthProvider.matchings[client.name]).build()
                )
                .setValue(
                    ValueMatcher.newBuilder().setListMatch(
                        ListMatcher.newBuilder().setOneOf(
                            ValueMatcher.newBuilder().setStringMatch(
                                StringMatcher.newBuilder()
                                    .setExact(client.selector)
                            )
                        )
                    )
                ).build()
        ).build()
    }

    private fun getSelectorMatching(
        client: ClientWithSelector,
        incomingPermissionsProperties: IncomingPermissionsProperties
    ): SelectorMatching? {
        val matching = incomingPermissionsProperties.selectorMatching[client.name]
        if (matching == null && client.selector != null) {
            logger.warn(
                "No selector matching found for client '${client.name}' with selector '${client.selector}' " +
                    "in EC properties. Source IP based authentication will not contain additional matching."
            )
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

    private fun tlsPrincipals(client: String): List<Principal> {
        val stringMatcher = sanUriMatcherFactory.createSanUriMatcher(client)

        return listOf(
            Principal.newBuilder().setAuthenticated(
                Principal.Authenticated.newBuilder()
                    .setPrincipalName(stringMatcher)
            ).build()
        )
    }

    private fun createStaticIpRanges(): Map<Client, Principal> {
        val ranges = incomingPermissionsProperties.sourceIpAuthentication.ipFromRange

        return ranges.mapValues {
            val principals = it.value.map { ipWithPrefix ->
                val (ip, prefixLength) = ipWithPrefix.split("/")

                Principal.newBuilder().setDirectRemoteIp(
                    CidrRange.newBuilder()
                        .setAddressPrefix(ip)
                        .setPrefixLen(UInt32Value.of(prefixLength.toInt())).build()
                )
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
        val resources = snapshot.endpoints
        val clusterLoadAssignment = resources[client.name]
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
                .setDirectRemoteIp(
                    CidrRange.newBuilder()
                        .setAddressPrefix(address.socketAddress.address)
                        .setPrefixLen(EXACT_IP_MASK).build()
                )
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

            Principal.newBuilder().setAndIds(
                Principal.Set.newBuilder().addAllIds(
                    listOf(sourceIpPrincipal, additionalMatchingPrincipal)
                ).build()
            ).build()
        } else {
            sourceIpPrincipal
        }
    }

    fun createHttpFilter(group: Group, snapshot: GlobalSnapshot): HttpFilter? {
        return if (incomingPermissionsProperties.enabled && group.proxySettings.incoming.permissionsEnabled) {
            val rules = getRules(
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
