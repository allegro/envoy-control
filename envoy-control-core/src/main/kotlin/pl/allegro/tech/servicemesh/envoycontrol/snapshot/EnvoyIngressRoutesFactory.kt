package pl.allegro.tech.servicemesh.envoycontrol.snapshot

import com.google.protobuf.Duration
import com.google.protobuf.UInt32Value
import com.google.protobuf.util.Durations
import io.envoyproxy.envoy.api.v2.RouteConfiguration
import io.envoyproxy.envoy.api.v2.core.DataSource
import io.envoyproxy.envoy.api.v2.route.DirectResponseAction
import io.envoyproxy.envoy.api.v2.route.HeaderMatcher
import io.envoyproxy.envoy.api.v2.route.RetryPolicy
import io.envoyproxy.envoy.api.v2.route.Route
import io.envoyproxy.envoy.api.v2.route.RouteAction
import io.envoyproxy.envoy.api.v2.route.RouteMatch
import io.envoyproxy.envoy.api.v2.route.VirtualCluster
import io.envoyproxy.envoy.api.v2.route.VirtualHost
import pl.allegro.tech.servicemesh.envoycontrol.groups.IncomingEndpoint
import pl.allegro.tech.servicemesh.envoycontrol.groups.PathMatchingType
import pl.allegro.tech.servicemesh.envoycontrol.groups.ProxySettings
import pl.allegro.tech.servicemesh.envoycontrol.groups.Role

internal class EnvoyIngressRoutesFactory(
    private val properties: SnapshotProperties
) {
    private fun clusterRouteAction(
        responseTimeout: Duration?,
        idleTimeout: Duration?,
        clusterName: String = "local_service"
    ): RouteAction.Builder {
        val timeoutResponse = responseTimeout ?: Durations.fromMillis(
            properties.localService.responseTimeout.toMillis()
        )
        val timeoutIdle = idleTimeout ?: Durations.fromMillis(properties.localService.idleTimeout.toMillis())
        return RouteAction.newBuilder()
            .setCluster(clusterName)
            .setTimeout(timeoutResponse)
            .setIdleTimeout(timeoutIdle)
    }

    private val statusPathPattern = properties.routes.status.pathPrefix + ".*"

    private fun statusRoute(localRouteAction: RouteAction.Builder): Route? {
        return Route.newBuilder()
            .setMatch(
                RouteMatch.newBuilder()
                    .setPrefix(properties.routes.status.pathPrefix)
                    .addHeaders(httpMethodMatcher(HttpMethod.GET))
            )
            .setRoute(localRouteAction)
            .build()
    }

    private val fallbackIngressRoute = Route.newBuilder()
        .setMatch(
            RouteMatch.newBuilder()
                .setPrefix("/")
        )
        .setDirectResponse(
            DirectResponseAction.newBuilder()
                .setStatus(properties.incomingPermissions.endpointUnavailableStatusCode)
                .setBody(
                    DataSource.newBuilder()
                        .setInlineString("Requested resource is unavailable or client not permitted")
                )
        )
        .build()

    private fun retryPolicy(retryProps: RetryPolicyProperties): RetryPolicy = RetryPolicy.newBuilder().apply {
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

    private fun allOpenIngressRoutes(localRouteAction: RouteAction.Builder): List<Route> {
        val nonRetryRoute = Route.newBuilder()
            .setMatch(
                RouteMatch.newBuilder()
                    .setPrefix("/")
            )
            .setRoute(localRouteAction)
            .build()
        val retryRoutes = perMethodRetryPolicies
            .map { (method, retryPolicy) ->
                Route.newBuilder()
                    .setMatch(
                        RouteMatch.newBuilder()
                            .addHeaders(httpMethodMatcher(method))
                            .setPrefix("/")
                    )
                    .setRoute(clusterRouteActionWithRetryPolicy(retryPolicy, localRouteAction))
                    .build()
            }
        return retryRoutes + nonRetryRoute
    }

    private fun customHealthCheckRoute(proxySettings: ProxySettings): List<Route> {
        if (proxySettings.incoming.healthCheck.hasCustomHealthCheck()) {
            val healthCheckRouteAction = clusterRouteAction(
                proxySettings.incoming.timeoutPolicy.responseTimeout,
                proxySettings.incoming.timeoutPolicy.idleTimeout,
                proxySettings.incoming.healthCheck.clusterName
            )
            return listOf(Route.newBuilder()
                .setMatch(
                    RouteMatch.newBuilder()
                        .setPrefix(proxySettings.incoming.healthCheck.path)
                        .addHeaders(httpMethodMatcher(HttpMethod.GET))
                )
                .setRoute(healthCheckRouteAction)
                .build())
        }
        return emptyList()
    }

    private fun localClusterRouteActionWithRetryPolicy(method: HttpMethod, localRouteAction: RouteAction.Builder):
        RouteAction.Builder = perMethodRetryPolicies[method]
        ?.let { clusterRouteActionWithRetryPolicy(it, localRouteAction) }
        ?: localRouteAction

    private fun clusterRouteActionWithRetryPolicy(
        retryPolicy: RetryPolicy,
        routeAction: RouteAction.Builder
    ) = routeAction.clone().setRetryPolicy(retryPolicy)

    fun createSecuredIngressRouteConfig(proxySettings: ProxySettings): RouteConfiguration {
        val virtualClusters = when (statusRouteVirtualClusterEnabled()) {
            true ->
                listOf(
                    VirtualCluster.newBuilder()
                        .setPattern(statusPathPattern)
                        .setName("status")
                        .build(),
                    VirtualCluster.newBuilder()
                        .setPattern("/.*")
                        .setName("endpoints")
                        .build()
                )
            false ->
                emptyList()
        }

        val adminRoutesFactory = AdminRoutesFactory(properties.routes)

        val virtualHost = VirtualHost.newBuilder()
            .setName("secured_local_service")
            .addDomains("*")
            .addAllVirtualClusters(virtualClusters)
            .addAllRoutes(adminRoutesFactory.generateAdminRoutes())
            .addAllRoutes(generateSecuredIngressRoutes(proxySettings))
            .also {
                if (properties.localService.retryPolicy.default.enabled) {
                    it.retryPolicy = defaultRetryPolicy
                }
            }

        return RouteConfiguration.newBuilder()
            .setName("ingress_secured_routes")
            .addVirtualHosts(virtualHost)
            .build()
    }

    private fun generateSecuredIngressRoutes(proxySettings: ProxySettings): List<Route> {
        val localRouteAction = clusterRouteAction(
            proxySettings.incoming.timeoutPolicy.responseTimeout,
            proxySettings.incoming.timeoutPolicy.idleTimeout
        )

        val customHealthCheckRoute = customHealthCheckRoute(proxySettings)

        if (!proxySettings.incoming.permissionsEnabled) {
            return customHealthCheckRoute + allOpenIngressRoutes(localRouteAction)
        }

        val rolesByName = proxySettings.incoming.roles.associateBy { it.name.orEmpty() }

        val applicationRoutes = proxySettings.incoming.endpoints
            .flatMap { toRoutes(it, rolesByName, localRouteAction) }

        return customHealthCheckRoute + listOfNotNull(
            statusRoute(localRouteAction).takeIf { properties.routes.status.enabled }
        ) + applicationRoutes + fallbackIngressRoute
    }

    private fun toRoutes(
        endpoint: IncomingEndpoint,
        roles: Map<String, Role>,
        localRouteAction: RouteAction.Builder
    ): List<Route> {
        val routeMatch = RouteMatch.newBuilder()

        when (endpoint.pathMatchingType) {
            PathMatchingType.PATH -> routeMatch.path = endpoint.path
            PathMatchingType.PATH_PREFIX -> routeMatch.prefix = endpoint.path
        }

        val clients = endpoint.clients
            .flatMap { roles[it]?.clients ?: listOf(it) }
            .distinct()

        val methods = endpoint.methods
            .takeIf { it.isNotEmpty() }
            ?.map { HttpMethod.valueOf(it) }
            ?: perMethodRetryPolicies.keys

        return clients.flatMap { client ->
            val routesForMethods = methods.map { method ->
                val match = routeMatch.clone()
                    .addHeaders(clientNameMatcher(client))
                    .addHeaders(httpMethodMatcher(method))
                Route.newBuilder()
                    .setMatch(match)
                    .setRoute(localClusterRouteActionWithRetryPolicy(method, localRouteAction))
                    .build()
            }
            if (endpoint.methods.isEmpty()) {
                val match = routeMatch.clone()
                    .addHeaders(clientNameMatcher(client))
                routesForMethods + Route.newBuilder()
                    .setMatch(match)
                    .setRoute(localRouteAction)
                    .build()
            } else routesForMethods
        }
    }

    private fun clientNameMatcher(clientName: String): HeaderMatcher.Builder =
        HeaderMatcher.newBuilder()
            .setName(properties.incomingPermissions.clientIdentityHeader)
            .setExactMatch(clientName)

    private fun statusRouteVirtualClusterEnabled() =
        properties.routes.status.enabled && properties.routes.status.createVirtualCluster
}
