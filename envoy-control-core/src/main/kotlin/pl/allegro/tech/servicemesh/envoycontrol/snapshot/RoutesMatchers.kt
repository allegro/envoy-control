package pl.allegro.tech.servicemesh.envoycontrol.snapshot

import io.envoyproxy.envoy.api.v2.route.HeaderMatcher

fun httpMethodMatcher(method: HttpMethod): HeaderMatcher = exactHeader(":method", method.name)

fun exactHeader(name: String, value: String): HeaderMatcher = HeaderMatcher.newBuilder()
    .setName(name)
    .setExactMatch(value)
    .build()
