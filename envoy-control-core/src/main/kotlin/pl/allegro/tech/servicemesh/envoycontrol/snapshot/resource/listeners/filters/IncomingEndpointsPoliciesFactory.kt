package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters

import com.google.protobuf.UInt32Value
import io.envoyproxy.controlplane.cache.SnapshotResources
import io.envoyproxy.envoy.config.core.v3.CidrRange
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment
import io.envoyproxy.envoy.config.rbac.v3.Policy
import io.envoyproxy.envoy.config.rbac.v3.Principal
import io.envoyproxy.envoy.config.route.v3.HeaderMatcher
import io.envoyproxy.envoy.type.matcher.v3.ListMatcher
import io.envoyproxy.envoy.type.matcher.v3.MetadataMatcher
import io.envoyproxy.envoy.type.matcher.v3.StringMatcher
import io.envoyproxy.envoy.type.matcher.v3.ValueMatcher
import pl.allegro.tech.servicemesh.envoycontrol.groups.ClientWithSelector
import pl.allegro.tech.servicemesh.envoycontrol.groups.Incoming
import pl.allegro.tech.servicemesh.envoycontrol.groups.IncomingEndpoint
import pl.allegro.tech.servicemesh.envoycontrol.groups.OAuth
import pl.allegro.tech.servicemesh.envoycontrol.groups.Role
import pl.allegro.tech.servicemesh.envoycontrol.logger
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.Client
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.IncomingPermissionsProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.JwtFilterProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.OAuthProvider
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SelectorMatching

class IncomingEndpointsPoliciesFactory(
    private val incomingPermissionsProperties: IncomingPermissionsProperties,
    private val rBACFilterPermissions: RBACFilterPermissions = RBACFilterPermissions(),
    private val jwtProperties: JwtFilterProperties = JwtFilterProperties()
) {
    private val incomingServicesSourceAuthentication = incomingPermissionsProperties
        .sourceIpAuthentication
        .ipFromServiceDiscovery
        .enabledForIncomingServices

    private val oAuthMatchingsClients: List<Client> = jwtProperties.providers.values.flatMap { it.matchings.keys }
    private val anyPrincipal = principalBuilder().setAny(true).build()
    private val denyForAllPrincipal = principalBuilder().setNotId(anyPrincipal).build()
    private val sanUriMatcherFactory = SanUriMatcherFactory(incomingPermissionsProperties.tlsAuthentication)

    private val strictPolicyPrincipal = buildPrincipalSetAndIds(
        metadataMatcherBuilder()
            .setFilter("envoy.filters.http.header_to_metadata")
            .addPath(pathSegmentBuilder().setKey("jwt-status").build())
            .setValue(valueMatcherBuilder().setStringMatch(stringMatcherBuilder().setExact("present")))
            .toPrincipal(),
        metadataMatcherBuilder()
            .setFilter("envoy.filters.http.jwt_authn")
            .addPath(pathSegmentBuilder().setKey(jwtProperties.payloadInMetadata))
            .addPath(pathSegmentBuilder().setKey(jwtProperties.fieldRequiredInToken))
            .setValue(valueMatcherBuilder().setPresentMatch(true))
            .toPrincipal()
    )
    private val allowMissingPolicyPrincipal = buildPrincipalSetOrIds(
        metadataMatcherBuilder()
            .setFilter("envoy.filters.http.header_to_metadata")
            .addPath(
                pathSegmentBuilder().setKey("jwt-status").build()
            )
            .setValue(
                valueMatcherBuilder().setStringMatch(stringMatcherBuilder().setExact("missing"))
            ).toPrincipal(),
        strictPolicyPrincipal
    )

    private val staticIpRanges = createStaticIpRanges()

    companion object {
        private val logger by logger()
        private val EXACT_IP_MASK = UInt32Value.of(32)
    }

    fun createIncomingEndpointPolicies(
        incomingPermissions: Incoming,
        snapshotEndpoints: SnapshotResources<ClusterLoadAssignment>
    ): List<EndpointWithPolicy> {
        val roles = incomingPermissions.roles
        val incomingPermissionsEndpoints = incomingPermissions.endpoints
        val principalCache = mutableMapOf<ClientWithSelector, List<Principal>>()

        return incomingPermissionsEndpoints.map { incomingEndpoint ->
            val clientsWithSelectors = resolveClientsWithSelectors(incomingEndpoint, roles)

            val principals = clientsWithSelectors
                .flatMap { client ->
                    getPrincipals(
                        principalCache,
                        client,
                        snapshotEndpoints,
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
            EndpointWithPolicy(
                incomingEndpoint,
                policy
            )
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

    private fun getPrincipals(
        principalCache: MutableMap<ClientWithSelector, List<Principal>>,
        client: ClientWithSelector,
        endpoints: SnapshotResources<ClusterLoadAssignment>,
        unlistedClientsPolicy: Incoming.UnlistedPolicy,
        oauth: OAuth?
    ): List<Principal> {
        val principals = principalCache.computeIfAbsent(client) {
            mapClientWithSelectorToPrincipals(it, endpoints)
        }.toMutableList()
        principals += principalForOAuthAndLogUnlistedClients(principals, unlistedClientsPolicy, oauth)
        return principals
    }

    private fun mergeWithOAuthPolicy(
        client: ClientWithSelector,
        principal: Principal,
        policy: OAuth.Policy?
    ): Principal {
        return if (client.name in oAuthMatchingsClients) {
            principal // don't merge if client has OAuth selector
        } else {
            when (policy) {
                OAuth.Policy.ALLOW_MISSING -> buildPrincipalSetAndIds(allowMissingPolicyPrincipal, principal)
                OAuth.Policy.STRICT -> buildPrincipalSetAndIds(strictPolicyPrincipal, principal)
                OAuth.Policy.ALLOW_MISSING_OR_FAILED -> principal
                null -> principal
            }
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

    private fun mapClientWithSelectorToPrincipals(
        clientWithSelector: ClientWithSelector,
        endpoints: SnapshotResources<ClusterLoadAssignment>
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

        return when {
            clientWithSelector.name in incomingServicesSourceAuthentication ->
                ipFromDiscoveryPrincipals(clientWithSelector, selectorMatching, endpoints)
            staticRangesForClient != null ->
                listOf(staticRangesForClient)
            providerForSelector != null && clientWithSelector.selector != null ->
                listOf(jwtClientWithSelectorPrincipal(clientWithSelector, providerForSelector))
            else ->
                tlsPrincipals(clientWithSelector.name)
        }
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

    private fun ipFromDiscoveryPrincipals(
        client: ClientWithSelector,
        selectorMatching: SelectorMatching?,
        endpoints: SnapshotResources<ClusterLoadAssignment>
    ): List<Principal> {
        val clusterLoadAssignment = endpoints.resources()[client.name]
        val sourceIpPrincipal = mapEndpointsToExactPrincipals(clusterLoadAssignment)

        return when {
            sourceIpPrincipal == null ->
                emptyList()
            client.selector != null && selectorMatching != null ->
                listOf(addAdditionalMatching(client.selector, selectorMatching, sourceIpPrincipal))
            else ->
                listOf(sourceIpPrincipal)
        }
    }

    private fun jwtClientWithSelectorPrincipal(client: ClientWithSelector, oAuthProvider: OAuthProvider): Principal =
        metadataMatcherBuilder()
            .setFilter("envoy.filters.http.jwt_authn")
            .addPath(pathSegmentBuilder().setKey(jwtProperties.payloadInMetadata).build())
            .addPath(pathSegmentBuilder().setKey(oAuthProvider.matchings[client.name]).build())
            .setValue(
                valueMatcherBuilder().setListMatch(
                    ListMatcher.newBuilder().setOneOf(
                        valueMatcherBuilder().setStringMatch(
                            stringMatcherBuilder()
                                .setExact(client.selector)
                        )
                    )
                )
            ).toPrincipal()

    private fun tlsPrincipals(client: String): List<Principal> {
        val stringMatcher = sanUriMatcherFactory.createSanUriMatcher(client)

        return listOf(
            principalBuilder().setAuthenticated(
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

                principalBuilder().setDirectRemoteIp(
                    CidrRange.newBuilder()
                        .setAddressPrefix(ip)
                        .setPrefixLen(UInt32Value.of(prefixLength.toInt())).build()
                )
                    .build()
            }

            buildPrincipalSetOrIds(principals)
        }
    }

    private fun addAdditionalMatching(
        selector: String,
        selectorMatching: SelectorMatching,
        sourceIpPrincipal: Principal
    ): Principal {
        return if (selectorMatching.header.isNotEmpty()) {
            val additionalMatchingPrincipal = principalBuilder()
                .setHeader(HeaderMatcher.newBuilder().setName(selectorMatching.header).setExactMatch(selector))
                .build()

            principalBuilder().setAndIds(
                Principal.Set.newBuilder().addAllIds(
                    listOf(sourceIpPrincipal, additionalMatchingPrincipal)
                ).build()
            ).build()
        } else {
            sourceIpPrincipal
        }
    }

    private fun mapEndpointsToExactPrincipals(clusterLoadAssignment: ClusterLoadAssignment?): Principal? {
        val principals = clusterLoadAssignment?.endpointsList?.flatMap { lbEndpoints ->
            lbEndpoints.lbEndpointsList.map { lbEndpoint ->
                lbEndpoint.endpoint.address
            }
        }.orEmpty().map { address ->
            principalBuilder()
                .setDirectRemoteIp(
                    CidrRange.newBuilder()
                        .setAddressPrefix(address.socketAddress.address)
                        .setPrefixLen(EXACT_IP_MASK).build()
                )
                .build()
        }

        return if (principals.isNotEmpty()) {
            principalBuilder().setOrIds(Principal.Set.newBuilder().addAllIds(principals).build()).build()
        } else {
            null
        }
    }

    private fun buildPrincipalSetAndIds(vararg principal: Principal): Principal =
        principalBuilder().setAndIds(
            Principal.Set.newBuilder().addAllIds(principal.toList()).build()
        ).build()

    private fun MetadataMatcher.Builder.toPrincipal() =
        principalBuilder().setMetadata(this.build()).build()

    private fun buildPrincipalSetOrIds(principals: Collection<Principal>): Principal =
        buildPrincipalSetOrIds(*principals.toTypedArray())

    private fun buildPrincipalSetOrIds(vararg principal: Principal): Principal =
        principalBuilder().setOrIds(
            Principal.Set.newBuilder().addAllIds(principal.toList()).build()
        ).build()

    private fun principalBuilder() = Principal.newBuilder()

    private fun stringMatcherBuilder() = StringMatcher.newBuilder()

    private fun valueMatcherBuilder() = ValueMatcher.newBuilder()

    private fun pathSegmentBuilder() = MetadataMatcher.PathSegment.newBuilder()

    private fun metadataMatcherBuilder() = MetadataMatcher.newBuilder()
}

data class EndpointWithPolicy(val endpoint: IncomingEndpoint, val policy: Policy.Builder)
