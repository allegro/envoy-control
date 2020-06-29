package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource

import io.envoyproxy.envoy.api.v2.route.HeaderMatcher
import pl.allegro.tech.servicemesh.envoycontrol.protocol.HttpMethod

fun httpMethodMatcher(method: HttpMethod): HeaderMatcher = exactHeader(":method", method.name)

fun exactHeader(name: String, value: String): HeaderMatcher = HeaderMatcher.newBuilder()
    .setName(name)
    .setExactMatch(value)
    .build()
