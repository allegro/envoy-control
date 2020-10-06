package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.routes

import io.envoyproxy.envoy.config.route.v3.Route

class AuthorizationRoute(
    val authorized: Route,
    val unauthorized: Route
)
