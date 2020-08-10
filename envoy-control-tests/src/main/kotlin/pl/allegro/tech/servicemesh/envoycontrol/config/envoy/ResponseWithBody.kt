package pl.allegro.tech.servicemesh.envoycontrol.config.envoy

import okhttp3.Response
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoContainer

data class ResponseWithBody(val response: Response, val body: String) {
    fun isFrom(echoContainer: EchoContainer) = body.contains(echoContainer.response)
    fun isOk() = response.isSuccessful
}
