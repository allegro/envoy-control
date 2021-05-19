package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.routes

import com.google.protobuf.util.Durations
import io.envoyproxy.controlplane.cache.TestResources
import io.envoyproxy.envoy.config.core.v3.HeaderValue
import io.envoyproxy.envoy.config.core.v3.HeaderValueOption
import io.envoyproxy.envoy.config.route.v3.DirectResponseAction
import io.envoyproxy.envoy.config.route.v3.InternalRedirectPolicy
import io.envoyproxy.envoy.config.route.v3.Route
import io.envoyproxy.envoy.config.route.v3.RouteAction
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration
import io.envoyproxy.envoy.config.route.v3.RouteMatch
import io.envoyproxy.envoy.config.route.v3.VirtualHost
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group
import pl.allegro.tech.servicemesh.envoycontrol.groups.ResourceVersion
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.RouteSpecification
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.EnvoyListenersFactory.Companion.DOMAIN_PROXY_LISTENER_ADDRESS

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
        addUpstreamAddressHeader: Boolean,
        resourceVersion: ResourceVersion = ResourceVersion.V3,
        routeName: String = "default_routes"
    ): RouteConfiguration {
        val virtualHosts = routes
            .filter { it.routeDomains.isNotEmpty() }
            .map { routeSpecification ->
                VirtualHost.newBuilder()
                    .setName(routeSpecification.clusterName)
                    .addAllDomains(routeSpecification.routeDomains)
                    .addRoutes(
                        Route.newBuilder()
                            .setMatch(
                                RouteMatch.newBuilder()
                                    .setPrefix("/")
                                    .build()
                            )
                            .setRoute(
                                createRouteAction(routeSpecification, resourceVersion)
                            ).build()
                    )
                    .build()
            }

        var routeConfiguration = RouteConfiguration.newBuilder()
            .setName(routeName)
            .addAllVirtualHosts(
                virtualHosts + originalDestinationRoute + wildcardRoute
            ).also {
                if (properties.incomingPermissions.enabled) {
                    it.addRequestHeadersToAdd(
                        HeaderValueOption.newBuilder()
                            .setHeader(
                                HeaderValue.newBuilder()
                                    .setKey(properties.incomingPermissions.serviceNameHeader)
                                    .setValue(serviceName)
                            )
                    )
                }
            }

        if (properties.egress.headersToRemove.isNotEmpty()) {
            routeConfiguration.addAllRequestHeadersToRemove(properties.egress.headersToRemove)
        }

        if (addUpstreamAddressHeader) {
            routeConfiguration = routeConfiguration.addResponseHeadersToAdd(upstreamAddressHeader)
        }

        return routeConfiguration.build()
    }

    /**
     * @see TestResources.createRoute
     */
    fun createEgressDomainRoutes(
        routes: Collection<RouteSpecification>,
        group: Group
    ): List<RouteConfiguration> {

        val portToDomain = group.proxySettings.outgoing.getDomainDependencies().filterNot { it.useSsl() }.groupBy(
            { it.getPort() }, { routes.filter { route -> route.clusterName == it.getClusterName() } }
        ).toMap()

        return portToDomain.filter { it.key != 80 }.map {
            val virtualHosts = routes
                .filter { it.routeDomains.isNotEmpty() }
                .map { routeSpecification ->
                    VirtualHost.newBuilder()
                        .setName(routeSpecification.clusterName)
                        .addAllDomains(routeSpecification.routeDomains)
                        .addRoutes(
                            Route.newBuilder()
                                .setMatch(
                                    RouteMatch.newBuilder()
                                        .setPrefix("/")
                                        .build()
                                )
                                .setRoute(
                                    createRouteAction(routeSpecification, group.version)
                                ).build()
                        )
                        .build()
                }
            val routeConfiguration = RouteConfiguration.newBuilder()
                .setName("$DOMAIN_PROXY_LISTENER_ADDRESS:${it.key}")
                .addAllVirtualHosts(
                    virtualHosts + wildcardRoute
                )
            if (properties.egress.headersToRemove.isNotEmpty()) {
                routeConfiguration.addAllRequestHeadersToRemove(properties.egress.headersToRemove)
            }
            routeConfiguration.build()
        }
    }

    private fun createRouteAction(
        routeSpecification: RouteSpecification,
        resourceVersion: ResourceVersion
    ): RouteAction.Builder {
        val routeAction = RouteAction.newBuilder()
            .setCluster(routeSpecification.clusterName)

        routeSpecification.settings.timeoutPolicy.let { timeoutPolicy ->
            timeoutPolicy.idleTimeout?.let { routeAction.setIdleTimeout(it) }
            timeoutPolicy.requestTimeout?.let { routeAction.setTimeout(it) }
        }

        if (routeSpecification.settings.handleInternalRedirect) {
            when (resourceVersion) {
                ResourceVersion.V2 ->
                    routeAction.setInternalRedirectAction(RouteAction.InternalRedirectAction.HANDLE_INTERNAL_REDIRECT)
                ResourceVersion.V3 ->
                    routeAction.internalRedirectPolicy = InternalRedirectPolicy.newBuilder().build()
            }
        }

        if (properties.egress.hostHeaderRewriting.enabled && routeSpecification.settings.rewriteHostHeader) {
            routeAction.hostRewriteHeader = properties.egress.hostHeaderRewriting.customHostHeader
        }

        return routeAction
    }
}
