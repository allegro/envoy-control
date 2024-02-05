package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.routes

import com.google.protobuf.BoolValue
import com.google.protobuf.Duration
import com.google.protobuf.UInt32Value
import com.google.protobuf.util.Durations
import io.envoyproxy.envoy.config.core.v3.HeaderValue
import io.envoyproxy.envoy.config.core.v3.HeaderValueOption
import io.envoyproxy.envoy.config.core.v3.Metadata
import io.envoyproxy.envoy.config.route.v3.HeaderMatcher
import io.envoyproxy.envoy.config.route.v3.RateLimit
import io.envoyproxy.envoy.config.route.v3.RetryPolicy
import io.envoyproxy.envoy.config.route.v3.Route
import io.envoyproxy.envoy.config.route.v3.RouteAction
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration
import io.envoyproxy.envoy.config.route.v3.RouteMatch
import io.envoyproxy.envoy.config.route.v3.VirtualCluster
import io.envoyproxy.envoy.config.route.v3.VirtualHost
import io.envoyproxy.envoy.type.matcher.v3.RegexMatcher
import io.envoyproxy.envoy.type.metadata.v3.MetadataKey
import pl.allegro.tech.servicemesh.envoycontrol.groups.ClientWithSelector
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group
import pl.allegro.tech.servicemesh.envoycontrol.groups.IncomingRateLimitEndpoint
import pl.allegro.tech.servicemesh.envoycontrol.groups.PathMatchingType
import pl.allegro.tech.servicemesh.envoycontrol.groups.ProxySettings
import pl.allegro.tech.servicemesh.envoycontrol.protocol.HttpMethod
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.EndpointMatch
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.LocalRetryPolicyProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.getRuleId
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters.EnvoyHttpFilters

typealias IngressMetadataFactory = (node: Group, currentZone: String) -> Metadata

class EnvoyIngressRoutesFactory(
    private val properties: SnapshotProperties,
    envoyHttpFilters: EnvoyHttpFilters = EnvoyHttpFilters.emptyFilters,
    private val currentZone: String
) {

    private val allClients = setOf(
        ClientWithSelector.create(properties.incomingPermissions.tlsAuthentication.wildcardClientIdentifier)
    )

    private val filterMetadata = envoyHttpFilters.ingressMetadata
    private fun clusterRouteAction(
        responseTimeout: Duration?,
        idleTimeout: Duration?,
        clusterName: String = "local_service",
        serviceName: String = "",
        rateLimitEndpoints: List<IncomingRateLimitEndpoint> = emptyList()
    ): RouteAction.Builder {
        val timeoutResponse = responseTimeout ?: Durations.fromMillis(
            properties.localService.responseTimeout.toMillis()
        )
        val timeoutIdle = idleTimeout ?: Durations.fromMillis(properties.localService.idleTimeout.toMillis())
        return RouteAction.newBuilder()
            .setCluster(clusterName)
            .setTimeout(timeoutResponse)
            .setIdleTimeout(timeoutIdle)
            .addIngressRateLimits(serviceName, rateLimitEndpoints)
    }

    private fun RouteAction.Builder.addIngressRateLimits(
        serviceName: String,
        rateLimitEndpoints: List<IncomingRateLimitEndpoint>
    ): RouteAction.Builder = apply {
        rateLimitEndpoints.forEach { endpoint ->
            val ruleId = getRuleId(serviceName, endpoint)
            val match = RateLimit.Action.HeaderValueMatch.newBuilder()
                .setDescriptorValue(ruleId)
                .addHeaders(createHeaderMatcher(endpoint.pathMatchingType, endpoint.path))

            if (endpoint.methods.isNotEmpty()) {
                match.addHeaders(HeaderMatcher.newBuilder()
                    .setRe2Match(endpoint.methods.joinToString(
                        separator = "|",
                        transform = { "^$it\$" }))
                    .setName(":method")
                )
            }
            if (endpoint.clients.isNotEmpty() && endpoint.clients != allClients) {
                match.addHeaders(HeaderMatcher.newBuilder()
                    .setRe2Match(endpoint.clients.joinToString(
                        separator = "|",
                        transform = { "^${it.compositeName()}\$" }))
                    .setName(properties.incomingPermissions.serviceNameHeader)
                )
            }
            addRateLimits(
                RateLimit.newBuilder()
                    .addActions(
                        RateLimit.Action.newBuilder()
                            .setHeaderValueMatch(match)
                    )
                    .setLimit(
                        RateLimit.Override.newBuilder()
                            .setDynamicMetadata(
                                RateLimit.Override.DynamicMetadata.newBuilder()
                                    .setMetadataKey(
                                        MetadataKey.newBuilder()
                                            .setKey("envoy.filters.http.ratelimit.override")
                                            .addPath(
                                                MetadataKey.PathSegment.newBuilder()
                                                    .setKey(ruleId)
                                            )
                                    )
                            )
                    )
            )
        }
    }

    private val statusEndpointsMatch: List<EndpointMatch> = properties.routes.status.endpoints

    private val endpointsPrefixMatcher = HeaderMatcher.newBuilder()
        .setName(":path").setPrefixMatch("/").build()

    private val statusMatcher: List<HeaderMatcher> = statusEndpointsMatch.map {
        createHeaderMatcher(it.matchingType, it.path)
    }

    private fun createHeaderMatcher(
        matchingType: PathMatchingType,
        path: String
    ) = when (matchingType) {
        PathMatchingType.PATH_PREFIX -> HeaderMatcher.newBuilder().setName(":path").setPrefixMatch(path).build()
        PathMatchingType.PATH -> HeaderMatcher.newBuilder().setName(":path").setExactMatch(path).build()
        PathMatchingType.PATH_REGEX -> HeaderMatcher.newBuilder().setName(":path").setRe2Match(path).build()
    }

    private fun HeaderMatcher.Builder.setRe2Match(regexPattern: String) = this
        .setSafeRegexMatch(
            RegexMatcher.newBuilder()
                .setRegex(regexPattern)
                .setGoogleRe2(
                    RegexMatcher.GoogleRE2.getDefaultInstance()
                )
                .build()
        )

    private val endpoints = listOf(
        VirtualCluster.newBuilder()
            .addHeaders(endpointsPrefixMatcher)
            .setName("endpoints")
            .build()
    )

    private val statusClusters = statusMatcher
        .map {
            VirtualCluster.newBuilder()
                .addHeaders(it)
                .setName("status")
                .build()
        }

    private fun retryPolicy(retryProps: LocalRetryPolicyProperties): RetryPolicy = RetryPolicy.newBuilder().apply {
        retryOn = retryProps.retryOn.joinToString(separator = ",")
        numRetries = UInt32Value.of(retryProps.numRetries)
        if (!retryProps.perTryTimeout.isZero) {
            perTryTimeout = Durations.fromMillis(retryProps.perTryTimeout.toMillis())
        }
        hostSelectionRetryMaxAttempts = retryProps.hostSelectionRetryMaxAttempts
        addAllRetriableStatusCodes(retryProps.retriableStatusCodes)
    }.build()

    val defaultRetryPolicy: RetryPolicy = retryPolicy(properties.localService.retryPolicy.default)
    val perMethodRetryPolicies: Map<HttpMethod, RetryPolicy> = properties.localService.retryPolicy.perHttpMethod
        .filter { it.value.enabled }
        .map { HttpMethod.valueOf(it.key) to retryPolicy(it.value) }
        .toMap()

    private fun ingressRoutes(localRouteAction: RouteAction.Builder, group: Group): List<Route> {
        val nonRetryRoute = Route.newBuilder()
            .setMatch(
                RouteMatch.newBuilder()
                    .setPrefix("/")
            )
            .setRoute(localRouteAction)
        val retryRoutes = perMethodRetryPolicies
            .map { (method, retryPolicy) ->
                Route.newBuilder()
                    .setMatch(
                        RouteMatch.newBuilder()
                            .addHeaders(httpMethodMatcher(method))
                            .setPrefix("/")
                    )
                    .setRoute(clusterRouteActionWithRetryPolicy(retryPolicy, localRouteAction))
            }
        return (retryRoutes + nonRetryRoute).map { builder ->
            builder.setMetadata(filterMetadata(group, currentZone)).build()
        }
    }

    private fun customHealthCheckRoute(proxySettings: ProxySettings): List<Route> {
        if (proxySettings.incoming.healthCheck.hasCustomHealthCheck()) {
            val healthCheckRouteAction = clusterRouteAction(
                proxySettings.incoming.timeoutPolicy.responseTimeout,
                proxySettings.incoming.timeoutPolicy.idleTimeout,
                proxySettings.incoming.healthCheck.clusterName
            )
            return listOf(
                Route.newBuilder()
                    .setMatch(
                        RouteMatch.newBuilder()
                            .setPrefix(proxySettings.incoming.healthCheck.path)
                            .addHeaders(httpMethodMatcher(HttpMethod.GET))
                    )
                    .setRoute(healthCheckRouteAction)
                    .build()
            )
        }
        return emptyList()
    }

    private fun clusterRouteActionWithRetryPolicy(
        retryPolicy: RetryPolicy,
        routeAction: RouteAction.Builder
    ) = routeAction.clone().setRetryPolicy(retryPolicy)

    fun createSecuredIngressRouteConfig(
        serviceName: String,
        proxySettings: ProxySettings,
        group: Group
    ): RouteConfiguration {
        val virtualClusters = when (statusRouteVirtualClusterEnabled()) {
            true -> {
                statusClusters + endpoints
            }
            false ->
                emptyList()
        }

        val adminRoutesFactory = AdminRoutesFactory(properties.routes)

        val virtualHost = VirtualHost.newBuilder()
            .setName("secured_local_service")
            .addDomains("*")
            .addAllVirtualClusters(virtualClusters)
            .addAllRoutes(adminRoutesFactory.generateAdminRoutes())
            .addAllRoutes(generateSecuredIngressRoutes(proxySettings, group))
            .also {
                if (properties.localService.retryPolicy.default.enabled) {
                    it.retryPolicy = defaultRetryPolicy
                }
            }
        val builder = RouteConfiguration.newBuilder()
            .setName("ingress_secured_routes")

        if (properties.ingress.headersToRemove.isNotEmpty()) {
            builder.addAllRequestHeadersToRemove(properties.ingress.headersToRemove)
        }
        if (properties.ingress.addServiceNameHeaderToResponse) {
            builder.addResponseHeadersToAdd(
                HeaderValueOption.newBuilder().setHeader(
                    HeaderValue.newBuilder()
                        .setKey(properties.ingress.serviceNameHeader)
                        .setValue(serviceName).build()
                ).setAppend(BoolValue.of(false))
            )
        }
        if (properties.ingress.addRequestedAuthorityHeaderToResponse) {
            builder.addResponseHeadersToAdd(
                HeaderValueOption.newBuilder().setHeader(
                    HeaderValue.newBuilder()
                        .setKey(properties.ingress.requestedAuthorityHeader)
                        .setValue("%REQ(:authority)%").build()
                ).setAppend(BoolValue.of(false))
            )
        }

        return builder
            .addVirtualHosts(virtualHost)
            .build()
    }

    private fun generateSecuredIngressRoutes(proxySettings: ProxySettings, group: Group): List<Route> {
        val localRouteAction = clusterRouteAction(
            proxySettings.incoming.timeoutPolicy.responseTimeout,
            proxySettings.incoming.timeoutPolicy.idleTimeout,
            serviceName = group.serviceName,
            rateLimitEndpoints = proxySettings.incoming.rateLimitEndpoints
        )

        val customHealthCheckRoute = customHealthCheckRoute(proxySettings)

        return customHealthCheckRoute + ingressRoutes(localRouteAction, group)
    }

    private fun statusRouteVirtualClusterEnabled() =
        properties.routes.status.enabled && properties.routes.status.createVirtualCluster
}
