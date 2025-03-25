package pl.allegro.tech.servicemesh.envoycontrol.config.service

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.convertValue
import okhttp3.Response
import org.testcontainers.containers.Network
import pl.allegro.tech.servicemesh.envoycontrol.config.containers.SSLGenericContainer
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.ResponseWithBody

class HttpsEchoContainer : SSLGenericContainer<HttpsEchoContainer>("mendhak/http-https-echo@$hash"),
    ServiceContainer, UpstreamService {

    companion object {
        // We need to use hash because the image doesn't use tags and the tests will fail if there is an older version
        // of the image pulled locally
        const val hash = "sha256:cd9025b7cdb6b2e8dd6e4a403d50b2dea074835948411167fc86566cb4ae77b6"
        const val PORT = 5678
    }

    override fun configure() {
        super.configure()
        withEnv("HTTP_PORT", "$PORT")
        withNetwork(Network.SHARED)
    }

    override fun port() = PORT
    override fun id(): String = containerId

    override fun isSourceOf(response: ResponseWithBody) = HttpsEchoResponse(response).isFrom(this)
}

class HttpsEchoResponse(val response: ResponseWithBody) {
    companion object {
        val objectMapper: ObjectMapper = ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    constructor(response: Response) : this(ResponseWithBody(response))

    val requestHeaders by lazy<Map<String, String>> {
        objectMapper.convertValue(objectMapper.readTree(response.body).at("/headers"))
    }

    val hostname by lazy { objectMapper.readTree(response.body).at("/os/hostname").textValue() }

    fun isFrom(container: HttpsEchoContainer): Boolean {
        return container.containerName() == hostname
    }
}

fun Response.asHttpsEchoResponse() = HttpsEchoResponse(this)
