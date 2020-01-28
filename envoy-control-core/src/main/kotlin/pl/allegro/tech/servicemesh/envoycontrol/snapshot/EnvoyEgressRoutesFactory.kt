package pl.allegro.tech.servicemesh.envoycontrol.snapshot

import io.envoyproxy.controlplane.cache.TestResources
import io.envoyproxy.envoy.api.v2.RouteConfiguration
import io.envoyproxy.envoy.api.v2.core.HeaderValue
import io.envoyproxy.envoy.api.v2.core.HeaderValueOption
import io.envoyproxy.envoy.api.v2.route.DirectResponseAction
import io.envoyproxy.envoy.api.v2.route.Route
import io.envoyproxy.envoy.api.v2.route.RouteAction
import io.envoyproxy.envoy.api.v2.route.RouteMatch
import io.envoyproxy.envoy.api.v2.route.VirtualHost

internal class EnvoyEgressRoutesFactory(
    private val properties: SnapshotProperties
) {

    /**
     * By default envoy doesn't proxy requests to provided IP address. We created cluster: envoy-original-destination
     * which allows direct calls to IP address extracted from x-envoy-original-dst-host header for calls to
     * envoy-original-destination cluster.
     */
    private val originalDestinationRoute = VirtualHost.newBuilder()
        .setName("original-destination-route")
        .addDomains("envoy-original-destination")
        .addRoutes(
            Route.newBuilder()
                .setMatch(
                    RouteMatch.newBuilder()
                        .setPrefix("/")
                )
                .setRoute(
                    RouteAction.newBuilder()
                        .setCluster("envoy-original-destination")
                )
        )
        .build()

    private val wildcardRoute = VirtualHost.newBuilder()
        .setName("wildcard-route")
        .addDomains("*")
        .addRoutes(
            Route.newBuilder()
                .setMatch(
                    RouteMatch.newBuilder()
                        .setPrefix("/")
                )
                .setDirectResponse(
                    DirectResponseAction.newBuilder()
                        .setStatus(properties.egress.clusterNotFoundStatusCode)
                )
        )
        .build()

    private val upstreamAddressHeader = HeaderValueOption.newBuilder().setHeader(
        HeaderValue.newBuilder().setKey("x-envoy-upstream-remote-address")
            .setValue("%UPSTREAM_REMOTE_ADDRESS%").build()
    )

    /**
     * @see TestResources.createRoute
     */
    fun createEgressRouteConfig(
        serviceName: String,
        routes: Collection<RouteSpecification>,
        addUpstreamAddressHeader: Boolean
    ): RouteConfiguration {
        val virtualHosts = routes.map { routeSpecification ->
            VirtualHost.newBuilder()
                .setName(routeSpecification.clusterName)
                .addDomains(routeSpecification.routeDomain)
                .addRoutes(
                    Route.newBuilder()
                        .setMatch(
                            RouteMatch.newBuilder()
                                .setPrefix("/")
                        )
                        .setRoute(
                            createRouteAction(routeSpecification)
                        )
                )
                .build()
        }

        var routeConfiguration = RouteConfiguration.newBuilder()
            .setName("default_routes")
            .addAllVirtualHosts(
                virtualHosts + originalDestinationRoute + wildcardRoute
            ).also {
                if (properties.incomingPermissions.enabled) {
                    it.addRequestHeadersToAdd(
                        HeaderValueOption.newBuilder()
                            .setHeader(
                                HeaderValue.newBuilder()
                                    .setKey(properties.incomingPermissions.clientIdentityHeader)
                                    .setValue(serviceName)
                            )
                    )
                }
            }
        if (addUpstreamAddressHeader)
            routeConfiguration = routeConfiguration.addResponseHeadersToAdd(upstreamAddressHeader)

        return routeConfiguration.build()
    }

    private fun createRouteAction(routeSpecification: RouteSpecification): RouteAction.Builder {
        val routeAction = RouteAction.newBuilder()
            .setCluster(routeSpecification.clusterName)

        routeSpecification.settings.timeoutPolicy?.let { timeoutPolicy ->
            timeoutPolicy.idleTimeout?.let { routeAction.setIdleTimeout(it) }
            timeoutPolicy.requestTimeout?.let { routeAction.setTimeout(it) }
        }

        if (routeSpecification.settings.handleInternalRedirect) {
            routeAction.setInternalRedirectAction(RouteAction.InternalRedirectAction.HANDLE_INTERNAL_REDIRECT)
        }

        return routeAction
    }
}
