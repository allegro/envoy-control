package pl.allegro.tech.servicemesh.envoycontrol.assertions

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.ObjectAssert
import pl.allegro.tech.servicemesh.envoycontrol.config.service.HttpsEchoContainer
import pl.allegro.tech.servicemesh.envoycontrol.config.service.HttpsEchoResponse
import java.util.function.Consumer

fun ObjectAssert<HttpsEchoResponse>.isOk(): ObjectAssert<HttpsEchoResponse> {
    matches { it.response.isOk() }
    return this
}

fun ObjectAssert<HttpsEchoResponse>.hasSNI(serverName: String): ObjectAssert<HttpsEchoResponse> = satisfies(Consumer {
    val actualServerName = HttpsEchoResponse.objectMapper.readTree(it.response.body).at("/connection/servername").textValue()
    assertThat(actualServerName).isEqualTo(serverName)
})

fun ObjectAssert<HttpsEchoResponse>.isFrom(container: HttpsEchoContainer) = satisfies(Consumer {
    assertThat(it.isFrom(container)).describedAs("response is not from the container").isTrue()
})
