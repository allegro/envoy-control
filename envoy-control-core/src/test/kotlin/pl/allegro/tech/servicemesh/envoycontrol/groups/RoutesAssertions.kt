package pl.allegro.tech.servicemesh.envoycontrol.groups

import com.google.protobuf.BoolValue
import com.google.protobuf.Duration
import io.envoyproxy.envoy.config.route.v3.DirectResponseAction
import io.envoyproxy.envoy.config.route.v3.HeaderMatcher
import io.envoyproxy.envoy.config.route.v3.RateLimit
import io.envoyproxy.envoy.config.route.v3.RedirectAction
import io.envoyproxy.envoy.config.route.v3.RetryPolicy
import io.envoyproxy.envoy.config.route.v3.Route
import io.envoyproxy.envoy.config.route.v3.RouteAction
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration
import io.envoyproxy.envoy.config.route.v3.VirtualCluster
import io.envoyproxy.envoy.config.route.v3.VirtualHost
import io.envoyproxy.envoy.config.route.v3.WeightedCluster
import io.envoyproxy.envoy.type.matcher.v3.RegexMatcher
import org.assertj.core.api.Assertions.assertThat
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.LocalRetryPolicyProperties

fun RouteConfiguration.hasSingleVirtualHostThat(condition: VirtualHost.() -> Unit): RouteConfiguration {
    assertThat(this.virtualHostsList).hasSize(1)
    condition(this.virtualHostsList[0])
    return this
}

fun RouteConfiguration.hasVirtualHostThat(name: String, condition: VirtualHost.() -> Unit): RouteConfiguration {
    condition(this.virtualHostsList.find { it.name == name }!!)
    return this
}

fun RouteConfiguration.hasClusterThat(name: String, condition: WeightedCluster.ClusterWeight?.() -> Unit): RouteConfiguration {
    condition(this.virtualHostsList
        .flatMap { it.routesList }
        .map { it.route }
        .flatMap { route -> route.weightedClusters.clustersList }
        .find { it.name == name }
    )
    return this
}

fun RouteConfiguration.hasRequestHeaderToAdd(key: String, value: String): RouteConfiguration {
    assertThat(this.requestHeadersToAddList).anySatisfy {
        assertThat(it.header.key).isEqualTo(key)
        assertThat(it.header.value).isEqualTo(value)
        assertThat(it.append).isEqualTo(BoolValue.of(false))
    }
    return this
}

fun RouteConfiguration.hasRequestHeadersToRemove(value: List<String>): RouteConfiguration {
    assertThat(this.requestHeadersToRemoveList).containsAll(value)
    return this
}

fun RouteConfiguration.hasResponseHeaderToAdd(key: String, value: String): RouteConfiguration {
    assertThat(this.responseHeadersToAddList).anySatisfy {
        assertThat(it.header.key).isEqualTo(key)
        assertThat(it.header.value).isEqualTo(value)
    }
    return this
}

fun RouteAction.hasCustomIdleTimeout(idleTimeout: Duration): RouteAction {
    assertThat(this.idleTimeout).isEqualTo(idleTimeout)
    return this
}

fun RouteAction.hasCustomRequestTimeout(requestTimeout: Duration): RouteAction {
    assertThat(this.timeout).isEqualTo(requestTimeout)
    return this
}

fun RouteAction.hostRewriteHeaderIsEmpty(): RouteAction {
    assertThat(this.hostRewriteHeader).isEmpty()
    return this
}

fun RouteAction.hasHostRewriteHeader(header: String): RouteAction {
    assertThat(this.hostRewriteHeader).isEqualTo(header)
    return this
}

fun RouteConfiguration.hasNoRequestHeaderToAdd(key: String): RouteConfiguration {
    assertThat(this.requestHeadersToAddList).noneSatisfy {
        assertThat(it.header.key).isEqualTo(key)
    }
    return this
}

fun RouteConfiguration.hasVirtualHostsInOrder(vararg conditions: (VirtualHost) -> Boolean): RouteConfiguration {
    assertThat(
        this.virtualHostsList.zip(conditions)
            .filter { (cluster, condition) -> condition(cluster) }
    ).hasSameSizeAs(conditions)
    return this
}

fun RouteConfiguration.hasNoResponseHeaderToAdd(key: String): RouteConfiguration {
    assertThat(this.responseHeadersToAddList).noneSatisfy {
        assertThat(it.header.key).isEqualTo(key)
    }
    return this
}

fun VirtualHost.hasStatusVirtualClusters(): VirtualHost {
    return this.hasVirtualClustersInOrder(
        {
            it.headersList == listOf(HeaderMatcher.newBuilder().setName(":path").setPrefixMatch("/status/").build()) &&
                it.name == "status"
        },
        {
            it.headersList == listOf(HeaderMatcher.newBuilder().setName(":path").setPrefixMatch("/").build()) &&
                it.name == "endpoints"
        }
    )
}

fun VirtualHost.hasVirtualClustersInOrder(vararg conditions: (VirtualCluster) -> Boolean): VirtualHost {
    assertThat(
        this.virtualClustersList.zip(conditions)
            .filter { (cluster, condition) -> condition(cluster) }
    ).hasSameSizeAs(conditions)
    return this
}

fun VirtualHost.hasOneDomain(domain: String): VirtualHost {
    assertThat(this.domainsList).hasSize(1).allMatch { it == domain }
    return this
}

fun VirtualHost.hasOnlyRoutesInOrder(vararg conditions: Route.() -> Unit): VirtualHost {
    assertThat(this.routesList).hasSameSizeAs(conditions)
    assertThat(
        this.routesList.zip(conditions)
            .map { (route, condition) -> condition(route) }
    ).hasSameSizeAs(conditions)
    return this
}

fun Route.matchingOnPrefix(prefix: String): Route {
    assertThat(this.match).matches { it.prefix == prefix && it.path == "" }
    return this
}

fun Route.prefixRewrite(prefixRewrite: String): Route {
    assertThat(this.route.prefixRewrite).isEqualTo(prefixRewrite)
    return this
}

fun Route.matchingOnPath(path: String): Route {
    assertThat(this.match).matches { it.path == path && it.prefix == "" }
    return this
}

fun Route.matchingOnHeader(name: String, value: String, isRegex: Boolean = false): Route {
    val matcher = RegexMatcher.newBuilder().setRegex(value)
        .setGoogleRe2(RegexMatcher.GoogleRE2.getDefaultInstance())
        .build()
    assertThat(this.match.headersList).anyMatch {
        if (isRegex) {
            it.name == name && it.safeRegexMatch == matcher
        } else {
            it.name == name && it.exactMatch == value
        }
    }
    return this
}

fun Route.matchingOnMethod(method: String, isRegex: Boolean = false): Route {
    return this.matchingOnHeader(":method", method, isRegex)
}

fun Route.matchingOnAnyMethod(): Route {
    assertThat(this.match.headersList).noneMatch { it.name == ":method" }
    return this
}

fun Route.publicAccess(): Route {
    assertThat(this.match.headersList).allMatch { it.name != "x-service-name" }
    return this
}

fun Route.accessOnlyForClient(client: String): Route {
    assertThat(this.match.headersList.filter { it.name == "x-service-name" })
        .hasSize(1)
        .allMatch { it.exactMatch == client }
    return this
}

fun Route.toCluster(cluster: String): Route {
    assertThat(this.route.cluster).isEqualTo(cluster)
    return this
}

fun Route.directResponse(condition: (DirectResponseAction) -> Boolean) {
    assertThat(this.directResponse).matches { condition(it) }
}

fun Route.redirect(condition: (RedirectAction) -> Boolean) {
    assertThat(this.redirect).matches { condition(it) }
}

fun Route.matchingRetryPolicy(properties: LocalRetryPolicyProperties) {
    matchingRetryPolicy(this.route.retryPolicy, properties)
}

fun VirtualHost.matchingRetryPolicy(properties: LocalRetryPolicyProperties) {
    matchingRetryPolicy(this.retryPolicy, properties)
}

fun matchingRetryPolicy(retryPolicy: RetryPolicy, properties: LocalRetryPolicyProperties) = retryPolicy.run {
    assertThat(retryOn).isEqualTo(properties.retryOn.joinToString(separator = ","))
    assertThat(numRetries.value).isEqualTo(properties.numRetries)
    assertThat(perTryTimeout.seconds).isEqualTo(properties.perTryTimeout.seconds)
    assertThat(hostSelectionRetryMaxAttempts).isEqualTo(properties.hostSelectionRetryMaxAttempts)
    assertThat(retriableStatusCodesList).containsExactlyInAnyOrderElementsOf(properties.retriableStatusCodes)
}

fun Route.matchingOnResponseTimeout(responseTimeout: Duration): Route {
    assertThat(this.route.timeout.seconds).isEqualTo(responseTimeout.seconds)
    return this
}

fun Route.matchingOnIdleTimeout(idleTimeout: Duration): Route {
    assertThat(this.route.idleTimeout.seconds).isEqualTo(idleTimeout.seconds)
    return this
}

fun Route.hasNoRetryPolicy() {
    assertThat(this.route.retryPolicy).isEqualTo(RetryPolicy.newBuilder().build())
}

fun Route.hasRetryPolicy() {
    assertThat(this.route.hasRetryPolicy()).isTrue()
}

fun Route.ingressServiceRoute() {
    this.matchingOnPrefix("/")
        .publicAccess()
        .toCluster("local_service")
}

fun Route.ingressStatusRoute() {
    this.matchingOnPrefix("/status/")
        .publicAccess()
        .toCluster("local_service")
}

fun Route.hasRateLimitsInOrder(vararg conditions: RateLimit.() -> Unit): Route = apply {
    assertThat(this.route.rateLimitsList).hasSameSizeAs(conditions)
    assertThat(
        this.route.rateLimitsList.zip(conditions)
            .map { (route, condition) -> condition(route) }
    ).hasSameSizeAs(conditions)
}

fun RateLimit.hasOnlyHeaderValueMatchActionWithMatchersInOrder(vararg conditions: HeaderMatcher.() -> Unit): RateLimit = apply {
    assertThat(this.actionsCount).isEqualTo(1)
    val action = this.getActions(0)
    assertThat(action.actionSpecifierCase).isEqualTo(RateLimit.Action.ActionSpecifierCase.HEADER_VALUE_MATCH)
    assertThat(action.headerValueMatch.headersList).hasSameSizeAs(conditions)
    assertThat(
        action.headerValueMatch.headersList.zip(conditions)
            .map { (route, condition) -> condition(route) }
    ).hasSameSizeAs(conditions)
}

fun HeaderMatcher.prefixPathMatcher(prefix: String) {
    assertThat(this.headerMatchSpecifierCase).isEqualTo(HeaderMatcher.HeaderMatchSpecifierCase.PREFIX_MATCH)
    assertThat(this.name).isEqualTo(":path")
    assertThat(this.prefixMatch).isEqualTo(prefix)
}

fun HeaderMatcher.pathMatcher(prefix: String) {
    assertThat(this.headerMatchSpecifierCase).isEqualTo(HeaderMatcher.HeaderMatchSpecifierCase.EXACT_MATCH)
    assertThat(this.name).isEqualTo(":path")
    assertThat(this.exactMatch).isEqualTo(prefix)
}

fun HeaderMatcher.googleRegexMethodMatcher(regex: String) = googleRegexMatcher(":method", regex)

fun HeaderMatcher.googleRegexPathMatcher(regex: String) = googleRegexMatcher(":path", regex)

fun HeaderMatcher.googleRegexMatcher(name: String, prefix: String) {
    assertThat(this.headerMatchSpecifierCase).isEqualTo(HeaderMatcher.HeaderMatchSpecifierCase.SAFE_REGEX_MATCH)
    assertThat(this.name).isEqualTo(name)
    assertThat(this.safeRegexMatch.engineTypeCase).isEqualTo(RegexMatcher.EngineTypeCase.GOOGLE_RE2)
    assertThat(this.safeRegexMatch.regex).isEqualTo(prefix)
}

fun configDumpAuthorizedRoute(): (Route) -> Unit = {
    it.matchingOnPrefix("/status/envoy/config_dump")
        .matchingOnMethod("GET")
        .toCluster("this_admin")
        .prefixRewrite("/config_dump")
        .matchingOnHeader("authorization", "test_token")
        .publicAccess()
}

fun configDumpRoute(): (Route) -> Unit = {
    it.matchingOnPrefix("/status/envoy/config_dump")
        .matchingOnMethod("GET")
        .publicAccess()
        .directResponse { it.status == 401 }
}

fun adminRoute(): (Route) -> Unit = {
    it.matchingOnPrefix("/status/envoy/")
        .toCluster("this_admin")
        .prefixRewrite("/")
        .publicAccess()
}

fun adminRedirectRoute(): (Route) -> Unit = {
    it.matchingOnPrefix("/status/envoy")
        .matchingOnMethod("GET")
        .publicAccess()
        .redirect { it.pathRedirect == "/status/envoy/" }
}

fun adminPostAuthorizedRoute(): (Route) -> Unit = {
    it.matchingOnPrefix("/status/envoy/")
        .matchingOnMethod("POST")
        .toCluster("this_admin")
        .prefixRewrite("/")
        .matchingOnHeader("authorization", "test_token")
        .publicAccess()
}

fun adminPostRoute(): (Route) -> Unit = {
    it.matchingOnPrefix("/status/envoy/")
        .matchingOnMethod("POST")
        .directResponse { it.status == 401 }
}
