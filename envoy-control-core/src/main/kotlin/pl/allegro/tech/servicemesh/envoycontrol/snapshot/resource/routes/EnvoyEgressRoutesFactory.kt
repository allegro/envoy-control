package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.routes

import com.google.protobuf.BoolValue
import com.google.protobuf.UInt32Value
import com.google.protobuf.util.Durations
import io.envoyproxy.controlplane.cache.TestResources
import io.envoyproxy.envoy.config.core.v3.HeaderValue
import io.envoyproxy.envoy.config.core.v3.HeaderValueOption
import io.envoyproxy.envoy.config.route.v3.DirectResponseAction
import io.envoyproxy.envoy.config.route.v3.HeaderMatcher
import io.envoyproxy.envoy.config.route.v3.InternalRedirectPolicy
import io.envoyproxy.envoy.config.route.v3.RetryPolicy
import io.envoyproxy.envoy.config.route.v3.Route
import io.envoyproxy.envoy.config.route.v3.RouteAction
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration
import io.envoyproxy.envoy.config.route.v3.RouteMatch
import io.envoyproxy.envoy.config.route.v3.VirtualHost
import io.envoyproxy.envoy.type.matcher.v3.RegexMatcher
import pl.allegro.tech.servicemesh.envoycontrol.groups.RetryBackOff
import pl.allegro.tech.servicemesh.envoycontrol.groups.RetryHostPredicate
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.RouteSpecification
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotProperties
import pl.allegro.tech.servicemesh.envoycontrol.groups.RetryPolicy as EnvoyControlRetryPolicy

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
                virtualHosts + originalDestinationRoute + wildcardRoute
            ).also {
                if (properties.incomingPermissions.enabled) {
                    it.addRequestHeadersToAdd(
                        HeaderValueOption.newBuilder()
                            .setHeader(
                                HeaderValue.newBuilder()
                                    .setKey(properties.incomingPermissions.serviceNameHeader)
                                    .setValue(serviceName)
                            ).setAppend(BoolValue.of(false))
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

    /**
     * @see TestResources.createRoute
     */
    fun createEgressDomainRoutes(
        routes: Collection<RouteSpecification>,
        routeName: String
    ): RouteConfiguration {
        val virtualHosts = routes
            .filter { route -> route.routeDomains.isNotEmpty() }
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
                                createRouteAction(routeSpecification)
                            ).build()
                    )
                    .build()
            }
        val routeConfiguration = RouteConfiguration.newBuilder()
            .setName(routeName)
            .addAllVirtualHosts(
                virtualHosts + originalDestinationRoute + wildcardRoute
            )
        if (properties.egress.headersToRemove.isNotEmpty()) {
            routeConfiguration.addAllRequestHeadersToRemove(properties.egress.headersToRemove)
        }
        return routeConfiguration.build()
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

        if (properties.egress.hostHeaderRewriting.enabled && routeSpecification.settings.rewriteHostHeader) {
            routeAction.hostRewriteHeader = properties.egress.hostHeaderRewriting.customHostHeader
        }

        return routeAction
    }
}

class RequestPolicyMapper private constructor() {
    companion object {
        fun mapToEnvoyRetryPolicyBuilder(retryPolicy: EnvoyControlRetryPolicy?): RetryPolicy? {
            return retryPolicy?.let { policy ->
                val retryPolicyBuilder = RetryPolicy.newBuilder()

                policy.retryOn?.let { retryPolicyBuilder.setRetryOn(it) }
                policy.hostSelectionRetryMaxAttempts?.let {
                    retryPolicyBuilder.setHostSelectionRetryMaxAttempts(it)
                }
                policy.numberRetries?.let { retryPolicyBuilder.setNumRetries(UInt32Value.of(it)) }
                policy.retryHostPredicate?.let {
                    buildRetryHostPredicate(it, retryPolicyBuilder)
                }
                policy.perTryTimeoutMs?.let { retryPolicyBuilder.setPerTryTimeout(Durations.fromMillis(it)) }
                policy.retryBackOff?.let {
                    buildRetryBackOff(it, retryPolicyBuilder)
                }
                policy.retryableStatusCodes?.let {
                    buildRetryableStatusCodes(it, retryPolicyBuilder)
                }
                policy.retryableHeaders?.let {
                    buildRetryableHeaders(it, retryPolicyBuilder)
                }

                retryPolicyBuilder.build()
            }
        }

        private fun buildRetryableHeaders(
            retryAbleHeaders: List<String>,
            retryPolicyBuilder: RetryPolicy.Builder
        ) {
            retryAbleHeaders.forEach { header ->
                retryPolicyBuilder.addRetriableHeaders(
                    HeaderMatcher.newBuilder().setName(header)
                )
            }
        }

        private fun buildRetryableStatusCodes(
            statusCodes: List<Int>,
            retryPolicyBuilder: RetryPolicy.Builder
        ) {
            retryPolicyBuilder.addAllRetriableStatusCodes(statusCodes)
        }

        private fun buildRetryBackOff(
            backOff: RetryBackOff,
            retryPolicyBuilder: RetryPolicy.Builder
        ): RetryPolicy.Builder? {
            val retryBackOffBuilder = RetryPolicy.RetryBackOff.newBuilder()
            backOff.baseInterval?.let { baseInterval ->
                retryBackOffBuilder.setBaseInterval(baseInterval)
            }
            backOff.maxInterval?.let { maxInterval ->
                retryBackOffBuilder.setMaxInterval(maxInterval)
            }
            return retryPolicyBuilder.setRetryBackOff(retryBackOffBuilder)
        }

        private fun buildRetryHostPredicate(
            hostPredicates: List<RetryHostPredicate>,
            retryPolicyBuilder: RetryPolicy.Builder
        ) {
            hostPredicates.map {
                RetryPolicy.RetryHostPredicate.newBuilder().setName(it.name).build()
            }.also {
                retryPolicyBuilder.addAllRetryHostPredicate(it)
            }
        }
    }
}
