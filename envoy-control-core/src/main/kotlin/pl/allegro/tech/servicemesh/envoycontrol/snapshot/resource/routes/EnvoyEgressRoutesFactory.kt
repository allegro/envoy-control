package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.routes

import com.google.protobuf.util.Durations
import io.envoyproxy.controlplane.cache.TestResources
import io.envoyproxy.envoy.config.core.v3.HeaderValue
import io.envoyproxy.envoy.config.core.v3.HeaderValueOption
import io.envoyproxy.envoy.config.route.v3.DirectResponseAction
import io.envoyproxy.envoy.config.route.v3.Route
import io.envoyproxy.envoy.config.route.v3.RouteAction
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration
import io.envoyproxy.envoy.config.route.v3.RouteMatch
import io.envoyproxy.envoy.config.route.v3.VirtualHost
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.RouteSpecification
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotProperties

class EnvoyEgressRoutesFactory(
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
                        .setIdleTimeout(Durations.fromMillis(properties.egress.commonHttp.idleTimeout.toMillis()))
                        .setTimeout(Durations.fromMillis(properties.egress.commonHttp.requestTimeout.toMillis()))
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
                                .build()
                        )
                        .setRoute(
                            createRouteAction(routeSpecification)
                        ).build()
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
        if (addUpstreamAddressHeader) {
            routeConfiguration = routeConfiguration.addResponseHeadersToAdd(upstreamAddressHeader)
        }

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

        if (properties.egress.hostHeaderRewriting.enabled && routeSpecification.settings.rewriteHostHeader) {
            routeAction
                    .setHostRewriteHeader(properties.egress.hostHeaderRewriting.customHostHeader)
        }

        return routeAction
    }
}
