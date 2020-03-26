package pl.allegro.tech.servicemesh.envoycontrol.config.containers

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.Response
import org.assertj.core.api.Assertions
import org.assertj.core.api.ObjectAssert
import pl.allegro.tech.servicemesh.envoycontrol.config.BaseEnvoyTest

class HttpsEchoContainer : SSLGenericContainer<HttpsEchoContainer>("marcinfalkowski/http-https-echo") {

    override fun configure() {
        super.configure()
        withNetwork(BaseEnvoyTest.network)
    }
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
