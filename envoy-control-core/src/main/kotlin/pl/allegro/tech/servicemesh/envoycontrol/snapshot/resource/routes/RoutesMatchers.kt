package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.routes

import io.envoyproxy.envoy.config.route.v3.HeaderMatcher
import pl.allegro.tech.servicemesh.envoycontrol.protocol.HttpMethod

fun httpMethodMatcher(method: HttpMethod): HeaderMatcher = exactHeader(":method", method.name)

fun exactHeader(name: String, value: String): HeaderMatcher = HeaderMatcher.newBuilder()
    .setName(name)
    .setExactMatch(value)
    .build()
