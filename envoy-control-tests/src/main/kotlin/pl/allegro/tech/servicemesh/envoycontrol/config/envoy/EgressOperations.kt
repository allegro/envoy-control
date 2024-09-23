package pl.allegro.tech.servicemesh.envoycontrol.config.envoy

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import org.assertj.core.api.Assertions.assertThat
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isOk
import pl.allegro.tech.servicemesh.envoycontrol.config.ClientsFactory
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.HttpResponseCloser.addToCloseableResponses
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension

class EgressOperations(val envoy: EnvoyContainer) {

    private val client by lazy { ClientsFactory.createClient() }

    fun callService(
        service: String,
        headers: Map<String, String> = mapOf(),
        pathAndQuery: String = "",
        method: String = "GET",
        body: RequestBody? = null
    ) = callWithHostHeader(service, headers, pathAndQuery, method, body)

    @Suppress("detekt.ForEachOnRange")
    fun callServiceRepeatedly(
        service: String,
        stats: CallStats,
        minRepeat: Int = 1,
        maxRepeat: Int = 100,
        repeatUntil: (ResponseWithBody) -> Boolean = { false },
        headers: Map<String, String> = mapOf(),
        pathAndQuery: String = "",
        assertNoErrors: Boolean = true
    ): CallStats {
        var conditionFulfilled = false
        (1..maxRepeat).asSequence()
            .map { i ->
                callService(service, headers, pathAndQuery).also {
                    if (assertNoErrors) {
                        assertThat(it).isOk().describedAs("Error response at attempt $i: \n$it")
                    }
                }
            }
            .map { ResponseWithBody(it) }
            .onEach { conditionFulfilled = conditionFulfilled || repeatUntil(it) }
            .withIndex()
            .takeWhile { (i, _) -> i < minRepeat || !conditionFulfilled }
            .map { it.value }
            .forEach { stats.addResponse(it) }
        return stats
    }

    fun callDomain(domain: String) = callWithHostHeader(domain, mapOf(), "")

    fun callServiceWithOriginalDst(service: EchoServiceExtension) =
        callWithHostHeader(
            "envoy-original-destination",
            mapOf("x-envoy-original-dst-host" to service.container().address()),
            ""
        )

    private fun callWithHostHeader(
        host: String,
        moreHeaders: Map<String, String>,
        pathAndQuery: String,
        method: String = "GET",
        body: RequestBody? = null
    ): Response {
        return client.newCall(
            Request.Builder()
                .method(method, body)
                .header("Host", host)
                .apply {
                    moreHeaders.forEach { name, value -> header(name, value) }
                }
                .url(envoy.egressListenerUrl().toHttpUrl().newBuilder(pathAndQuery)!!.build())
                .build()
        )
            .execute().addToCloseableResponses()
    }
}
