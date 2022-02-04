package pl.allegro.tech.servicemesh.envoycontrol.config.envoy

import okhttp3.Headers
import okhttp3.Headers.Companion.headersOf
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import pl.allegro.tech.servicemesh.envoycontrol.config.ClientsFactory
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.HttpResponseCloser.addToCloseableResponses
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension

class IngressOperations(val envoy: EnvoyContainer) {

    private val client by lazy { ClientsFactory.createClient() }
    private val insecureClient by lazy { ClientsFactory.createInsecureClient() }

    fun callLocalService(endpoint: String, headers: Headers = headersOf()): Response =
        callLocalService(endpoint, headers, client)

    fun callLocalServiceInsecure(endpoint: String, headers: Headers = headersOf(), useTls: Boolean = false): Response =
        callLocalService(endpoint, headers, insecureClient, useTls)

    fun callPostLocalService(endpoint: String, headers: Headers, body: RequestBody): Response =
            client.newCall(
                    Request.Builder()
                            .post(body)
                            .headers(headers)
                            .url(envoy.ingressListenerUrl() + endpoint)
                            .build()
            )
                    .execute().addToCloseableResponses()

    fun callServiceWithOriginalDst(service: EchoServiceExtension, path: String = "", serviceName: String, useTls: Boolean = false) =
        callLocalServiceInsecure(
            path,
            mapOf("x-envoy-original-dst-host" to service.container().address(),
                "host" to "envoy-original-destination", "x-service-name" to serviceName).toHeaders(),
            useTls
        )

    private fun callLocalService(
        endpoint: String,
        headers: Headers,
        client: OkHttpClient,
        useTls: Boolean = false
    ): Response =
        client.newCall(
            Request.Builder()
                .get()
                .headers(headers)
                .url(envoy.ingressListenerUrl(useTls) + endpoint)
                .build()
        )
            .execute().addToCloseableResponses()
}
