package pl.allegro.tech.servicemesh.envoycontrol.config.service

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.convertValue
import okhttp3.Response
import org.testcontainers.containers.Network
import pl.allegro.tech.servicemesh.envoycontrol.config.containers.SSLGenericContainer

class HttpsEchoContainer : SSLGenericContainer<HttpsEchoContainer>("mendhak/http-https-echo:30"),
    ServiceContainer {

    companion object {
        const val PORT = 5678
    }

    override fun configure() {
        super.configure()
        withEnv("HTTP_PORT", "$PORT")
        withNetwork(Network.SHARED)
    }

    override fun port() = PORT
}

class HttpsEchoResponse(val response: Response) {
    companion object {
        val objectMapper = ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    val body = response.use { it.body?.string() } ?: ""

    val requestHeaders by lazy<Map<String, String>> {
        objectMapper.convertValue(objectMapper.readTree(body).at("/headers"))
    }

    val hostname by lazy { objectMapper.readTree(body).at("/os/hostname").textValue() }
}

fun Response.asHttpsEchoResponse() = HttpsEchoResponse(this)
