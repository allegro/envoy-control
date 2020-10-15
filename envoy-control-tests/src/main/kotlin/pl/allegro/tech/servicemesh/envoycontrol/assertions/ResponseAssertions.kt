package pl.allegro.tech.servicemesh.envoycontrol.assertions

import okhttp3.Response
import org.assertj.core.api.ObjectAssert
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension

fun ObjectAssert<Response>.isOk(): ObjectAssert<Response> {
    matches { it.isSuccessful }
    return this
}

fun ObjectAssert<Response>.isForbidden(): ObjectAssert<Response> {
    matches({
        it.body()?.close()
        it.code() == 403
    }, "is forbidden")
    return this
}

fun ObjectAssert<Response>.isUnreachable(): ObjectAssert<Response> {
    matches({
        it.body()?.close()
        it.code() == 503 || it.code() == 504
    }, "is unreachable")
    return this
}

fun ObjectAssert<Response>.isFrom(echoServiceExtension: EchoServiceExtension): ObjectAssert<Response> {
    matches {
        it.body()?.use { it.string().contains(echoServiceExtension.container().response) } ?: false
    }
    return this
}

fun ObjectAssert<Response>.hasXEnvoyUpstreamRemoteAddressFrom(echoServiceExtension: EchoServiceExtension): ObjectAssert<Response> {
    matches { it.headers("x-envoy-upstream-remote-address").contains(echoServiceExtension.container().address()) }
    return this
}
