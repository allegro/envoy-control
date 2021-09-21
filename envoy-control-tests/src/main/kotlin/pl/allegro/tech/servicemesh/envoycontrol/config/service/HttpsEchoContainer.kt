package pl.allegro.tech.servicemesh.envoycontrol.config.service

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.convertValue
import okhttp3.Response
import pl.allegro.tech.servicemesh.envoycontrol.config.BaseEnvoyTest
import pl.allegro.tech.servicemesh.envoycontrol.config.containers.SSLGenericContainer

class HttpsEchoContainer : SSLGenericContainer<HttpsEchoContainer>("mendhak/http-https-echo@$hash"),
    ServiceContainer {

    companion object {
        // We need to use hash because the image doesn't use tags and the tests will fail if there is an older version
        // of the image pulled locally
        const val hash = "sha256:cd9025b7cdb6b2e8dd6e4a403d50b2dea074835948411167fc86566cb4ae77b6"
        const val PORT = 5678
    }

    override fun configure() {
        super.configure()
        withEnv("HTTP_PORT", "$PORT")
        withNetwork(BaseEnvoyTest.network)
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
