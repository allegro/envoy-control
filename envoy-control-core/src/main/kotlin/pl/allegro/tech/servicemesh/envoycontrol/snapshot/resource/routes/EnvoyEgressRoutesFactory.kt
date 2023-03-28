package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.routes

import com.google.protobuf.Any
import com.google.protobuf.BoolValue
import com.google.protobuf.UInt32Value
import com.google.protobuf.util.Durations
import com.google.protobuf.util.Structs
import com.google.protobuf.util.Values
import io.envoyproxy.controlplane.cache.TestResources
import io.envoyproxy.envoy.config.core.v3.HeaderValue
import io.envoyproxy.envoy.config.core.v3.HeaderValueOption
import io.envoyproxy.envoy.config.core.v3.Metadata
import io.envoyproxy.envoy.config.route.v3.DirectResponseAction
import io.envoyproxy.envoy.config.route.v3.HeaderMatcher
import io.envoyproxy.envoy.config.route.v3.InternalRedirectPolicy
import io.envoyproxy.envoy.config.route.v3.RetryPolicy
import io.envoyproxy.envoy.config.route.v3.RetryPolicy.ResetHeaderFormat
import io.envoyproxy.envoy.config.route.v3.Route
import io.envoyproxy.envoy.config.route.v3.RouteAction
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration
import io.envoyproxy.envoy.config.route.v3.RouteMatch
import io.envoyproxy.envoy.config.route.v3.VirtualHost
import io.envoyproxy.envoy.extensions.retry.host.omit_canary_hosts.v3.OmitCanaryHostsPredicate
import io.envoyproxy.envoy.extensions.retry.host.omit_host_metadata.v3.OmitHostMetadataConfig
import io.envoyproxy.envoy.extensions.retry.host.previous_hosts.v3.PreviousHostsPredicate
import io.envoyproxy.envoy.type.matcher.v3.RegexMatcher
import pl.allegro.tech.servicemesh.envoycontrol.groups.ListenersConfig.AddUpstreamServiceTagsCondition
import pl.allegro.tech.servicemesh.envoycontrol.groups.RateLimitedRetryBackOff
import pl.allegro.tech.servicemesh.envoycontrol.groups.RetryBackOff
import pl.allegro.tech.servicemesh.envoycontrol.groups.RetryHostPredicate
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.RouteSpecification
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters.ServiceTagFilterFactory
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

    private val upstreamTagsHeader = HeaderValueOption.newBuilder().apply {
        val metadataKey = properties.routing.serviceTags.metadataKey
        header = HeaderValue.newBuilder()
            .setKey("x-envoy-upstream-service-tags")
            // doesn't work in envoy <= v1.23.x. Envoy crashes. Works as expected in v1.24.x
            .setValue("%UPSTREAM_METADATA(envoy.lb:$metadataKey)%")
            .build()
        appendAction = HeaderValueOption.HeaderAppendAction.OVERWRITE_IF_EXISTS_OR_ADD
        keepEmptyValue = false
    }.build()

    private val defaultRouteMatch = RouteMatch
        .newBuilder()
        .setPrefix("/")
        .build()

    /**
     * @see TestResources.createRoute
     */
    fun createEgressRouteConfig(
        serviceName: String,
        routes: Collection<RouteSpecification>,
        addUpstreamAddressHeader: Boolean,
        addUpstreamServiceTagsHeader: AddUpstreamServiceTagsCondition = AddUpstreamServiceTagsCondition.NEVER,
        routeName: String = "default_routes"
    ): RouteConfiguration {
        val virtualHosts = routes
            .filter { it.routeDomains.isNotEmpty() }
            .map { routeSpecification ->
                buildEgressVirtualHost(routeSpecification, addUpstreamServiceTagsHeader)
            }

        val routeConfiguration = RouteConfiguration.newBuilder()
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
            routeConfiguration.addResponseHeadersToAdd(upstreamAddressHeader)
        }

        if (addUpstreamServiceTagsHeader == AddUpstreamServiceTagsCondition.ALWAYS) {
            routeConfiguration.addResponseHeadersToAdd(upstreamTagsHeader)
        }

        return routeConfiguration.build()
    }

    private fun buildEgressVirtualHost(
        routeSpecification: RouteSpecification,
        addUpstreamServiceTagsHeader: AddUpstreamServiceTagsCondition
    ): VirtualHost {
        val virtualHost = VirtualHost.newBuilder()
            .setName(routeSpecification.clusterName)
            .addAllDomains(routeSpecification.routeDomains)
        val retryPolicy = routeSpecification.settings.retryPolicy
        if (retryPolicy != null) {
            buildEgressRouteWithRetryPolicy(virtualHost, retryPolicy, routeSpecification)
        } else {
            virtualHost.addRoutes(
                buildRoute(
                    routeSpecification = routeSpecification,
                    match = defaultRouteMatch,
                    shouldAddRetryPolicy = false
                )
            )
        }

        if (properties.routing.serviceTags.isAutoServiceTagEffectivelyEnabled()) {
            addServiceTagHeaders(routeSpecification, virtualHost, addUpstreamServiceTagsHeader)
        }

        return virtualHost.build()
    }

    private fun addServiceTagHeaders(
        routeSpecification: RouteSpecification,
        virtualHost: VirtualHost.Builder,
        addUpstreamServiceTagsHeader: AddUpstreamServiceTagsCondition
    ) {
        val routingPolicy = routeSpecification.settings.routingPolicy
        if (routingPolicy.autoServiceTag) {
            val tagsPreferenceJoined = routingPolicy.serviceTagPreference.joinToString("|")
            virtualHost.addRequestHeadersToAdd(
                HeaderValueOption.newBuilder()
                    .setHeader(
                        HeaderValue.newBuilder()
                            .setKey(properties.routing.serviceTags.preferenceHeader)
                            .setValue(tagsPreferenceJoined)
                    )
                    .setAppendAction(HeaderValueOption.HeaderAppendAction.OVERWRITE_IF_EXISTS_OR_ADD)
                    .setKeepEmptyValue(false)
            )
            if (addUpstreamServiceTagsHeader == AddUpstreamServiceTagsCondition.WHEN_SERVICE_TAG_PREFERENCE_IS_USED) {
                virtualHost.addResponseHeadersToAdd(upstreamTagsHeader)
            }
        }
    }

    private fun buildRoute(
        routeSpecification: RouteSpecification, match: RouteMatch, shouldAddRetryPolicy: Boolean
    ): Route {
        val routeBuilder = Route.newBuilder()
            .setMatch(match)
            .setRoute(createRouteAction(routeSpecification, shouldAddRetryPolicy))

        if (properties.routing.serviceTags.isAutoServiceTagEffectivelyEnabled()) {
            addServiceTagMetadata(routeSpecification, routeBuilder)
        }

        return routeBuilder.build()
    }

    private fun addServiceTagMetadata(routeSpecification: RouteSpecification, routeBuilder: Route.Builder) {
        val routingPolicy = routeSpecification.settings.routingPolicy
        if (routingPolicy.autoServiceTag) {
            val serviceTagsListProto = Values.of(routingPolicy.serviceTagPreference.map { Values.of(it) })
            val serviceTagMetadataKeyProto = Values.of(properties.routing.serviceTags.metadataKey)
            routeBuilder.metadata = Metadata.newBuilder()
                .putFilterMetadata(
                    "envoy.filters.http.lua",
                    Structs.of(
                        ServiceTagFilterFactory.AUTO_SERVICE_TAG_PREFERENCE_METADATA, serviceTagsListProto,
                        ServiceTagFilterFactory.SERVICE_TAG_METADATA_KEY_METADATA, serviceTagMetadataKeyProto
                    )
                )
                .build()
        }
    }

    private fun buildEgressRouteWithRetryPolicy(
        virtualHost: VirtualHost.Builder,
        retryPolicy: EnvoyControlRetryPolicy,
        routeSpecification: RouteSpecification
    ): VirtualHost.Builder {
        return if (retryPolicy.methods != null) {
            val regexAsAString = retryPolicy.methods.joinToString(separator = "|")
            val retryRouteMatch = RouteMatch
                .newBuilder()
                .setPrefix("/")
                .addHeaders(buildMethodHeaderMatcher(regexAsAString))
                .build()
            virtualHost.addRoutes(
                buildRoute(
                    routeSpecification = routeSpecification,
                    match = retryRouteMatch,
                    shouldAddRetryPolicy = true
                )
            ).addRoutes(
                buildRoute(
                    routeSpecification = routeSpecification,
                    match = defaultRouteMatch,
                    shouldAddRetryPolicy = false
                )
            )
        } else {
            virtualHost.addRoutes(
                buildRoute(
                    routeSpecification = routeSpecification,
                    match = defaultRouteMatch,
                    shouldAddRetryPolicy = true
                )
            )
        }
    }

    private fun buildMethodHeaderMatcher(regexAsAString: String) = HeaderMatcher.newBuilder()
        .setName(":method")
        .setSafeRegexMatch(
            RegexMatcher.newBuilder()
                .setRegex(regexAsAString)
                .setGoogleRe2(RegexMatcher.GoogleRE2.getDefaultInstance())
                .build()
        )

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
                            .setMatch(defaultRouteMatch)
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

                policy.retryOn?.let { retryPolicyBuilder.setRetryOn(it.joinToString { joined -> joined }) }
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
                policy.rateLimitedRetryBackOff?.let {
                    buildRateLimitedRetryBackOff(it, retryPolicyBuilder)
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

        private fun buildRateLimitedRetryBackOff(
            rateLimitedRetryBackOff: RateLimitedRetryBackOff,
            retryPolicyBuilder: RetryPolicy.Builder
        ) {
            rateLimitedRetryBackOff.resetHeaders?.forEach {
                retryPolicyBuilder.rateLimitedRetryBackOffBuilder
                    .addResetHeaders(
                        RetryPolicy.ResetHeader.newBuilder()
                            .setName(it.name)
                            .setFormat(parseResetHeaderFormat(it.format))
                    )
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
                RetryPolicy.RetryHostPredicate.newBuilder()
                    .setName(it.predicateName)
                    .setTypedConfig(it.toRetryHostPredicate())
                    .build()
            }.also {
                retryPolicyBuilder.addAllRetryHostPredicate(it)
            }
        }

        private fun RetryHostPredicate.toRetryHostPredicate(): Any {
            val any = when (this) {
                RetryHostPredicate.PREVIOUS_HOSTS -> PreviousHostsPredicate.newBuilder().build()
                RetryHostPredicate.OMIT_CANARY_HOST -> OmitCanaryHostsPredicate.newBuilder().build()
                RetryHostPredicate.OMIT_HOST_METADATA -> OmitHostMetadataConfig.newBuilder()
                    .build()
            }
            return Any.pack(any)
        }

        private fun parseResetHeaderFormat(format: String): ResetHeaderFormat {
            return when (format) {
                "SECONDS" -> ResetHeaderFormat.SECONDS
                "UNIX_TIMESTAMP" -> ResetHeaderFormat.UNIX_TIMESTAMP
                else -> ResetHeaderFormat.UNRECOGNIZED
            }
        }
    }
}
