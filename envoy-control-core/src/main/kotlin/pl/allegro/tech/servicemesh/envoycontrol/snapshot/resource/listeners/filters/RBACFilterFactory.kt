package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters

import com.google.protobuf.Any
import io.envoyproxy.controlplane.cache.SnapshotResources
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment
import io.envoyproxy.envoy.config.rbac.v3.Policy
import io.envoyproxy.envoy.config.rbac.v3.Principal
import io.envoyproxy.envoy.config.rbac.v3.RBAC
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter
import pl.allegro.tech.servicemesh.envoycontrol.groups.ClientWithSelector
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group
import pl.allegro.tech.servicemesh.envoycontrol.groups.Incoming
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.GlobalSnapshot
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.IncomingPermissionsProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.StatusRouteProperties
import io.envoyproxy.envoy.extensions.filters.http.rbac.v3.RBAC as RBACFilter

class RBACFilterFactory(
    private val incomingPermissionsProperties: IncomingPermissionsProperties,
    statusRouteProperties: StatusRouteProperties,
    private val rBACFilterPermissions: RBACFilterPermissions = RBACFilterPermissions(),
    private val incomingEndpointsPoliciesFactory: IncomingEndpointsPoliciesFactory
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
    private val fullAccessClients = incomingPermissionsProperties.clientsAllowedToAllEndpoints.map {
        ClientWithSelector(name = it)
    }
    private val sanUriMatcherFactory = SanUriMatcherFactory(incomingPermissionsProperties.tlsAuthentication)
    private val statusRoutePolicy = createStatusRoutePolicy(statusRouteProperties)

    init {
        incomingPermissionsProperties.selectorMatching.forEach {
            if (it.key !in incomingServicesIpRangeAuthentication && it.key !in incomingServicesSourceAuthentication) {
                throw IllegalArgumentException("${it.key} is not defined in ip range or ip from discovery section.")
            }
        }
    }

    companion object {
        private const val ALLOW_UNLISTED_POLICY_NAME = "ALLOW_UNLISTED_POLICY"
        private const val ALLOW_LOGGED_POLICY_NAME = "ALLOW_LOGGED_POLICY"
        private const val STATUS_ROUTE_POLICY_NAME = "STATUS_ALLOW_ALL_POLICY"
        private const val HTTP_FILTER_NAME = "envoy.filters.http.rbac"
    }

    // TODO why not only pass proxySettings and endpoints from global snapshot?
    fun createHttpFilter(group: Group, snapshot: GlobalSnapshot): HttpFilter? =
        createHttpFilter(group.proxySettings.incoming, snapshot.endpoints)

    fun createHttpFilter(incoming: Incoming, endpoints: SnapshotResources<ClusterLoadAssignment>): HttpFilter? =
        if (incomingPermissionsProperties.enabled && incoming.permissionsEnabled) {
            val rules = getRules(incoming, endpoints)
            val rbacFilter = RBACFilter.newBuilder()
                .setRules(rules.actualRules)
                .setShadowRules(rules.shadowRules)
                .build()

            HttpFilter.newBuilder()
                .setName(HTTP_FILTER_NAME)
                .setTypedConfig(Any.pack(rbacFilter)).build()
        } else {
            null
        }

    private fun getRules(
        incomingPermissions: Incoming,
        snapshotEndpoints: SnapshotResources<ClusterLoadAssignment>
    ): Rules {
        val incomingEndpointsPolicies = incomingEndpointsPoliciesFactory.createIncomingEndpointPolicies(
            incomingPermissions, snapshotEndpoints
        )
        val restrictedEndpointsPolicies = getRestrictedEndpointsPolicies(incomingEndpointsPolicies)
        val loggedEndpointsPolicies = getLoggedEndpointsPolicies(incomingEndpointsPolicies)
        val allowUnlistedPolicies = unlistedAndLoggedEndpointsPolicies(
            incomingPermissions,
            (statusRoutePolicy + restrictedEndpointsPolicies).values,
            loggedEndpointsPolicies.values
        )

        val shadowRules = buildShadowRules(restrictedEndpointsPolicies, loggedEndpointsPolicies)
        val actualRules = buildActualRules(restrictedEndpointsPolicies, allowUnlistedPolicies)
        return Rules(shadowRules = shadowRules, actualRules = actualRules)
    }

    private fun getRestrictedEndpointsPolicies(
        incomingEndpointsPolicies: List<EndpointWithPolicy>
    ): Map<String, Policy.Builder> =
        incomingEndpointsPolicies.getFilteredEndpointPolicyMapping {
            it.endpoint.unlistedClientsPolicy == Incoming.UnlistedPolicy.BLOCKANDLOG ||
                it.endpoint.oauth?.policy != null
        }

    private fun getLoggedEndpointsPolicies(
        incomingEndpointsPolicies: List<EndpointWithPolicy>
    ): Map<String, Policy.Builder> =
        incomingEndpointsPolicies.getFilteredEndpointPolicyMapping {
            it.endpoint.unlistedClientsPolicy == Incoming.UnlistedPolicy.LOG && it.endpoint.oauth?.policy == null
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

    private fun buildShadowRules(
        restrictedEndpointsPolicies: Map<String, Policy.Builder>,
        loggedEndpointsPolicies: Map<String, Policy.Builder>
    ): RBAC {
        val shadowPolicies = buildPolicies(restrictedEndpointsPolicies, loggedEndpointsPolicies)
        return RBAC.newBuilder()
            .setAction(RBAC.Action.ALLOW)
            .putAllPolicies(shadowPolicies)
            .build()
        // build needs to be called before any modifications happen so we do not have to clone it
    }

    private fun buildActualRules(
        restrictedEndpointsPolicies: Map<String, Policy.Builder>,
        allowUnlistedPolicies: Map<String, Policy.Builder>
    ): RBAC {
        val restrictedEndpointsPoliciesWithFullAccessClient = restrictedEndpointsPolicies.mapValues {
            addFullAccessClients(it.value)
        }
        val actualPolicies =
            buildPolicies(restrictedEndpointsPoliciesWithFullAccessClient, allowUnlistedPolicies)
        return RBAC.newBuilder()
            .setAction(RBAC.Action.ALLOW)
            .putAllPolicies(actualPolicies)
            .build()
    }

    private fun buildPolicies(
        restrictedEndpointsPolicies: Map<String, Policy.Builder>,
        loggedEndpointsPolicies: Map<String, Policy.Builder>
    ) =
        (statusRoutePolicy + restrictedEndpointsPolicies + loggedEndpointsPolicies)
            .map { (endpoint, policy) -> endpoint to policy.build() }.toMap()

    private fun Iterable<EndpointWithPolicy>.getFilteredEndpointPolicyMapping(
        predicate: (EndpointWithPolicy) -> Boolean
    ) =
        asSequence()
            .filter(predicate)
            .map { (endpoint, policy) -> "$endpoint" to policy }.toMap()

    private fun addFullAccessClients(policyBuilder: Policy.Builder): Policy.Builder =
        policyBuilder.addAllPrincipals(fullAccessClients.flatMap { clientWithSelector ->
            tlsPrincipals(clientWithSelector.name)
        })

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

    private fun tlsPrincipals(client: String): List<Principal> {
        val stringMatcher = sanUriMatcherFactory.createSanUriMatcher(client)

        return listOf(
            Principal.newBuilder().setAuthenticated(
                Principal.Authenticated.newBuilder()
                    .setPrincipalName(stringMatcher)
            ).build()
        )
    }
}

private data class Rules(val shadowRules: RBAC, val actualRules: RBAC)
