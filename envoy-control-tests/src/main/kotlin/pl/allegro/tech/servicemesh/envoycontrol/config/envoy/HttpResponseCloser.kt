package pl.allegro.tech.servicemesh.envoycontrol.config.envoy

import okhttp3.Response

object HttpResponseCloser {

    private val responses = mutableListOf<Response>()

    fun closeResponses() {
        responses.forEach(this::closeResponse)
        responses.clear()
    }

    private fun closeResponse(response: Response) {
        runCatching { response.close() }
    }

    fun Response.addToCloseableResponses() : Response {
        responses.add(this)
        return this
    }
}
