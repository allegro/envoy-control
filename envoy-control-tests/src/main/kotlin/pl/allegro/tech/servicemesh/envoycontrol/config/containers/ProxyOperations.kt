package pl.allegro.tech.servicemesh.envoycontrol.config.containers

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.HttpResponseCloser.addToCloseableResponses
import java.time.Duration

class ProxyOperations(val address: String) {
    private val client = OkHttpClient.Builder()
        .readTimeout(Duration.ofSeconds(20))
        .build()

    fun call(pathAndQuery: String): Response {
        return client.newCall(
            Request.Builder()
                .get()
                .url(address.toHttpUrl().newBuilder(pathAndQuery)!!.build())
                .build()
        )
            .execute().addToCloseableResponses()
    }
}
