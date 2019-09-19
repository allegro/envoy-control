package pl.allegro.tech.servicemesh.envoycontrol.snapshot

import io.envoyproxy.envoy.api.v2.route.Route

class AuthorizationRoute(
    val authorized: Route,
    val unauthorized: Route
)
