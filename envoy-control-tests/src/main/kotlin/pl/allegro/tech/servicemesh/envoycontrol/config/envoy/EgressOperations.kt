package pl.allegro.tech.servicemesh.envoycontrol.config.envoy

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.assertj.core.api.Assertions.assertThat
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isOk
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension
import java.time.Duration

class EgressOperations(val envoy: EnvoyContainer) {

    private val client = OkHttpClient.Builder()
            // envoys default timeout is 15 seconds while OkHttp is 10
            .readTimeout(Duration.ofSeconds(20))
            .build()

    fun callService(service: String, headers: Map<String, String> = mapOf(), pathAndQuery: String = "") =
        callWithHostHeader(service, headers, pathAndQuery)

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
                .map { ResponseWithBody(it, it.body()?.string() ?: "") }
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
            mapOf("x-envoy-original-dst-host" to service.container.address()),
            ""
        )

    private fun callWithHostHeader(host: String, moreHeaders: Map<String, String>, pathAndQuery: String): Response {
        return client.newCall(
            Request.Builder()
                .get()
                .header("Host", host)
                .apply {
                    moreHeaders.forEach { name, value -> header(name, value) }
                }
                .url(HttpUrl.get(envoy.egressListenerUrl()).newBuilder(pathAndQuery)!!.build())
                .build()
        )
            .execute()
    }
}
