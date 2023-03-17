package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.routes

import com.google.protobuf.BoolValue
import io.envoyproxy.controlplane.cache.TestResources
import io.envoyproxy.envoy.config.core.v3.HeaderValue
import io.envoyproxy.envoy.config.core.v3.HeaderValueOption
import io.envoyproxy.envoy.config.route.v3.DirectResponseAction
import io.envoyproxy.envoy.config.route.v3.HeaderMatcher
import io.envoyproxy.envoy.config.route.v3.InternalRedirectPolicy
import io.envoyproxy.envoy.config.route.v3.Route
import io.envoyproxy.envoy.config.route.v3.RouteAction
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration
import io.envoyproxy.envoy.config.route.v3.RouteMatch
import io.envoyproxy.envoy.config.route.v3.VirtualHost
import io.envoyproxy.envoy.type.matcher.v3.RegexMatcher
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.RouteSpecification
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotProperties

class EnvoyIngresGatewayIngressRoutesFactory(
    private val properties: SnapshotProperties
) {
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
    fun createIngressRouteConfig(
        serviceName: String,
        routes: Collection<RouteSpecification>,
        addUpstreamAddressHeader: Boolean,
        routeName: String = "default_routes"
    ): RouteConfiguration {
        val virtualHosts = routes
            .filter { it.routeDomains.isNotEmpty() }
            .map { routeSpecification ->
                addMultipleRoutes(
                    VirtualHost.newBuilder()
                        .setName(routeSpecification.clusterName)
                        .addAllDomains(routeSpecification.routeDomains),
                    routeSpecification
                ).build()
            }

        var routeConfiguration = RouteConfiguration.newBuilder()
            .setName(routeName)
            .addAllVirtualHosts(
                virtualHosts + wildcardRoute
            ).also {
                if (properties.incomingPermissions.enabled) {
                    it.addRequestHeadersToAdd(
                        HeaderValueOption.newBuilder()
                            .setHeader(
                                HeaderValue.newBuilder()
                                    .setKey(properties.incomingPermissions.serviceNameHeader)
                                    .setValue(serviceName)
                            ).setAppend(BoolValue.of(true))
                    )
                }
            }

        if (addUpstreamAddressHeader) {
            routeConfiguration = routeConfiguration.addResponseHeadersToAdd(upstreamAddressHeader)
        }

        return routeConfiguration.build()
    }

    private fun addMultipleRoutes(
        addAllDomains: VirtualHost.Builder,
        routeSpecification: RouteSpecification
    ): VirtualHost.Builder {
        routeSpecification.settings.retryPolicy.let {
            buildRouteForRetryPolicy(addAllDomains, routeSpecification)
        }
        buildDefaultRoute(addAllDomains, routeSpecification)
        return addAllDomains
    }

    private fun buildRouteForRetryPolicy(
        addAllDomains: VirtualHost.Builder,
        routeSpecification: RouteSpecification
    ): VirtualHost.Builder? {
        val regexAsAString = routeSpecification.settings.retryPolicy.methods?.joinToString(separator = "|")
        val routeMatchBuilder = RouteMatch
            .newBuilder()
            .setPrefix("/")
            .also { routeMatcher ->
                regexAsAString?.let {
                    routeMatcher.addHeaders(buildMethodHeaderMatcher(it))
                }
            }

        return addAllDomains.addRoutes(
            Route.newBuilder()
                .setMatch(routeMatchBuilder.build())
                .setRoute(createRouteAction(routeSpecification, shouldAddRetryPolicy = true))
                .build()
        )
    }

    private fun buildMethodHeaderMatcher(regexAsAString: String) = HeaderMatcher.newBuilder()
        .setName(":method")
        .setSafeRegexMatch(
            RegexMatcher.newBuilder()
                .setRegex(regexAsAString)
                .setGoogleRe2(RegexMatcher.GoogleRE2.getDefaultInstance())
                .build()
        )

    private fun buildDefaultRoute(
        addAllDomains: VirtualHost.Builder,
        routeSpecification: RouteSpecification
    ) {
        addAllDomains.addRoutes(
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
    }

    private fun createRouteAction(
        routeSpecification: RouteSpecification,
        shouldAddRetryPolicy: Boolean = false
    ): RouteAction.Builder {
        val routeAction = RouteAction.newBuilder()
            .setCluster(routeSpecification.clusterName)

        routeSpecification.settings.timeoutPolicy.let { timeoutPolicy ->
            timeoutPolicy.idleTimeout?.let { routeAction.setIdleTimeout(it) }
            timeoutPolicy.requestTimeout?.let { routeAction.setTimeout(it) }
        }

        if (shouldAddRetryPolicy) {
            routeSpecification.settings.retryPolicy.let { policy ->
                routeAction.setRetryPolicy(RequestPolicyMapper.mapToEnvoyRetryPolicyBuilder(policy))
            }
        }

        if (routeSpecification.settings.handleInternalRedirect) {
            routeAction.internalRedirectPolicy = InternalRedirectPolicy.newBuilder().build()
        }

        return routeAction
    }
}
