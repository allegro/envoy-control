package pl.allegro.tech.servicemesh.envoycontrol.groups

import com.google.protobuf.Duration
import io.envoyproxy.envoy.api.v2.RouteConfiguration
import io.envoyproxy.envoy.api.v2.route.DirectResponseAction
import io.envoyproxy.envoy.api.v2.route.RedirectAction
import io.envoyproxy.envoy.api.v2.route.RetryPolicy
import io.envoyproxy.envoy.api.v2.route.Route
import io.envoyproxy.envoy.api.v2.route.VirtualCluster
import io.envoyproxy.envoy.api.v2.route.VirtualHost
import org.assertj.core.api.Assertions.assertThat
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.RetryPolicyProperties

fun RouteConfiguration.hasSingleVirtualHostThat(condition: VirtualHost.() -> Unit): RouteConfiguration {
    assertThat(this.virtualHostsList).hasSize(1)
    condition(this.virtualHostsList[0])
    return this
}

fun RouteConfiguration.hasHeaderToAdd(key: String, value: String): RouteConfiguration {
    assertThat(this.requestHeadersToAddList).anySatisfy {
        assertThat(it.header.key).isEqualTo(key)
        assertThat(it.header.value).isEqualTo(value)
    }
    return this
}

fun RouteConfiguration.hasNoHeaderToAdd(key: String): RouteConfiguration {
    assertThat(this.requestHeadersToAddList).noneSatisfy {
        assertThat(it.header.key).isEqualTo(key)
    }
    return this
}

fun VirtualHost.hasStatusVirtualClusters(): VirtualHost {
    return this.hasVirtualClustersInOrder(
        { it.pattern == "/status/.*" && it.name == "status" },
        { it.pattern == "/.*" && it.name == "endpoints" }
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

fun Route.matchingOnHeader(name: String, value: String): Route {
    assertThat(this.match.headersList).anyMatch {
        it.name == name && it.exactMatch == value
    }
    return this
}

fun Route.matchingOnMethod(method: String): Route {
    return this.matchingOnHeader(":method", method)
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
    assertThat(this.directResponse).satisfies { condition(it) }
}

fun Route.redirect(condition: (RedirectAction) -> Boolean) {
    assertThat(this.redirect).satisfies { condition(it) }
}

fun Route.matchingRetryPolicy(properties: RetryPolicyProperties) {
    matchingRetryPolicy(this.route.retryPolicy, properties)
}

fun VirtualHost.matchingRetryPolicy(properties: RetryPolicyProperties) {
    matchingRetryPolicy(this.retryPolicy, properties)
}

fun matchingRetryPolicy(retryPolicy: RetryPolicy, properties: RetryPolicyProperties) = retryPolicy.run {
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

fun Route.allOpenIngressRoute() {
    this.matchingOnPrefix("/")
        .publicAccess()
        .toCluster("local_service")
}

fun fallbackIngressRoute(): (Route) -> Unit = {
    it.matchingOnPrefix("/")
        .publicAccess()
        .directResponse { it.status == 503 }
}

fun statusRoute(
    idleTimeout: Duration? = null,
    responseTimeout: Duration? = null,
    clusterName: String = "local_service",
    healthCheckPath: String = "/status/"
): (Route) -> Unit = {
    it.matchingOnPrefix(healthCheckPath)
        .matchingOnMethod("GET")
        .publicAccess()
        .toCluster(clusterName)
    if (responseTimeout != null) {
        it.matchingOnResponseTimeout(responseTimeout)
    }
    if (idleTimeout != null) {
        it.matchingOnIdleTimeout(idleTimeout)
    }
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
