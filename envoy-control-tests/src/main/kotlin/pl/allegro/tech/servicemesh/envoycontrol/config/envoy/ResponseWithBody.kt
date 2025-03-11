package pl.allegro.tech.servicemesh.envoycontrol.config.envoy

import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.Response
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoContainer

data class ResponseWithBody(val response: Response) {

    companion object {
        private val objectMapper by lazy { ObjectMapper() }
    }

    val body = response.body?.string() ?: ""

    fun isFrom(echoContainer: EchoContainer) = body.contains(echoContainer.response)
    fun isOk() = response.isSuccessful
}
