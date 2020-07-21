package pl.allegro.tech.servicemesh.envoycontrol.config.envoy

import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import java.time.Duration

class IngressOperations(val envoy: EnvoyContainer) {

    private val client = OkHttpClient.Builder()
            // envoys default timeout is 15 seconds while OkHttp is 10
            .readTimeout(Duration.ofSeconds(20))
            .build()

    fun callLocalService(endpoint: String, headers: Headers): Response =
            client.newCall(
                    Request.Builder()
                            .get()
                            .headers(headers)
                            .url(envoy.ingressListenerUrl() + endpoint)
                            .build()
            )
                    .execute()

    fun callPostLocalService(endpoint: String, headers: Headers, body: RequestBody): Response =
            client.newCall(
                    Request.Builder()
                            .post(body)
                            .headers(headers)
                            .url(envoy.ingressListenerUrl() + endpoint)
                            .build()
            )
                    .execute()
}
