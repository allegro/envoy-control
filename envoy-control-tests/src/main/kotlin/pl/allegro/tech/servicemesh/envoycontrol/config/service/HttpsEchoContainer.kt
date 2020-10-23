package pl.allegro.tech.servicemesh.envoycontrol.config.service

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.Response
import org.assertj.core.api.Assertions
import org.assertj.core.api.ObjectAssert
import pl.allegro.tech.servicemesh.envoycontrol.config.BaseEnvoyTest
import pl.allegro.tech.servicemesh.envoycontrol.config.containers.SSLGenericContainer

class HttpsEchoContainer : SSLGenericContainer<HttpsEchoContainer>("mendhak/http-https-echo@$hash"),
    ServiceContainer {

    companion object {
        // We need to use hash because the image doesn't use tags and the tests will fail if there is an older version
        // of the image pulled locally
        const val hash = "sha256:6b69d5da0245157d7f9d06dfb65d0dd25fbedf5389a66d912c806572d02b0d1d"
        const val PORT = 80
    }

    override fun configure() {
        super.configure()
        withNetwork(BaseEnvoyTest.network)
    }

    override fun port() = PORT
}

class HttpsEchoResponse(val response: Response) {
    companion object {
        val objectMapper = ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    val body = response.use { it.body()?.string() } ?: ""
}

fun ObjectAssert<HttpsEchoResponse>.hasSNI(serverName: String): ObjectAssert<HttpsEchoResponse> = satisfies {
    val actualServerName = HttpsEchoResponse.objectMapper.readTree(it.body).at("/connection/servername").textValue()
    Assertions.assertThat(actualServerName).isEqualTo(serverName)
}
