package pl.allegro.tech.servicemesh.envoycontrol.config.envoy

import okhttp3.Response
import pl.allegro.tech.servicemesh.envoycontrol.config.service.UpstreamService

data class ResponseWithBody(val response: Response) {
    val body = response.use { it.body?.string() } ?: ""
    fun isFrom(upstreamService: UpstreamService) = upstreamService.isSourceOf(this)
    fun isOk() = response.isSuccessful
}
