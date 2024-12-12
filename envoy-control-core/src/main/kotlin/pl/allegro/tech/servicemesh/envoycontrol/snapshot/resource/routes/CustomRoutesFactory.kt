package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.routes

import io.envoyproxy.envoy.config.route.v3.Route
import io.envoyproxy.envoy.config.route.v3.RouteAction
import io.envoyproxy.envoy.config.route.v3.RouteMatch
import io.envoyproxy.envoy.type.matcher.v3.RegexMatcher
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.RoutesProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.StringMatcherType

class CustomRoutesFactory(properties: RoutesProperties) {

    val routes: List<Route> = properties.customs.filter { it.enabled }.map {
        val matcher = when (it.path.type) {
            StringMatcherType.REGEX -> RouteMatch.newBuilder()
                .setSafeRegex(
                    RegexMatcher.newBuilder()
                        .setRegex(it.path.value)
                        .setGoogleRe2(RegexMatcher.GoogleRE2.getDefaultInstance())
                )
            StringMatcherType.EXACT -> RouteMatch.newBuilder().setPath(it.path.value)
            StringMatcherType.PREFIX -> RouteMatch.newBuilder().setPrefix(it.path.value)
        }
        RouteMatch.newBuilder()
        Route.newBuilder()
            .setName(it.cluster)
            .setRoute(RouteAction.newBuilder()
                .setCluster(it.cluster)
                .also { route ->
                    if (it.prefixRewrite != "") {
                        route.setPrefixRewrite(it.prefixRewrite)
                    }
                }
            )
            .setMatch(matcher)
            .build()
    }

    fun generateCustomRoutes() = routes
}
