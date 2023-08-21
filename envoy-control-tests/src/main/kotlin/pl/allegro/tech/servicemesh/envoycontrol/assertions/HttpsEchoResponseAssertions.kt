package pl.allegro.tech.servicemesh.envoycontrol.assertions

import org.assertj.core.api.Assertions
import org.assertj.core.api.ObjectAssert
import pl.allegro.tech.servicemesh.envoycontrol.config.service.HttpsEchoContainer
import pl.allegro.tech.servicemesh.envoycontrol.config.service.HttpsEchoResponse
import java.util.function.Consumer

fun ObjectAssert<HttpsEchoResponse>.isOk(): ObjectAssert<HttpsEchoResponse> {
    matches { it.response.isSuccessful }
    return this
}

fun ObjectAssert<HttpsEchoResponse>.hasSNI(serverName: String): ObjectAssert<HttpsEchoResponse> = satisfies(Consumer {
    val actualServerName = HttpsEchoResponse.objectMapper.readTree(it.body).at("/connection/servername").textValue()
    Assertions.assertThat(actualServerName).isEqualTo(serverName)
})

fun ObjectAssert<HttpsEchoResponse>.isFrom(container: HttpsEchoContainer) = satisfies(Consumer {
    Assertions.assertThat(container.containerName()).isEqualTo(it.hostname)
})
