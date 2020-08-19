@file:Suppress("FunctionName")

package pl.allegro.tech.servicemesh.envoycontrol

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import okhttp3.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import pl.allegro.tech.servicemesh.envoycontrol.chaos.api.NetworkDelay
import pl.allegro.tech.servicemesh.envoycontrol.chaos.api.NetworkDelayResponse
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration

private val sampleNetworkDelayRequest = NetworkDelay(
    source = "sample source",
    delay = "1m",
    duration = "1s",
    target = "sample target"
)

internal class ChaosControllerTest : EnvoyControlTestConfiguration() {

    val objectMapper: ObjectMapper = ObjectMapper()
        .registerModule(KotlinModule())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    companion object {
        @JvmStatic
        @BeforeAll
        fun setupTest() {
            setup()
        }
    }

    @Test
    fun `should return UNAUTHORIZED for invalid user`() {
        // when
        val response = envoyControl1.postChaosFaultRequest(
            username = "bad user",
            password = "wrong pass",
            networkDelay = sampleNetworkDelayRequest
        )

        // then
        assertThat(response.code()).isEqualTo(HttpStatus.UNAUTHORIZED.value())
    }

    @Test
    fun `should post a chaos fault request and get response with storage object`() {
        // when
        val response = convertResponseToNetworkDelayResponse(
            envoyControl1.postChaosFaultRequest(networkDelay = sampleNetworkDelayRequest)
        )

        // then
        with(response) {
            assertThat(id).isNotEmpty()
            assertThat(source).isEqualTo(sampleNetworkDelayRequest.source)
            assertThat(delay).isEqualTo(sampleNetworkDelayRequest.delay)
            assertThat(duration).isEqualTo(sampleNetworkDelayRequest.duration)
            assertThat(target).isEqualTo(sampleNetworkDelayRequest.target)
        }
    }

    private fun convertResponseToNetworkDelayResponse(response: Response): NetworkDelayResponse =
        response.body()
            ?.use { objectMapper.readValue(it.byteStream(), NetworkDelayResponse::class.java) }
            ?: throw ChaosFaultInvalidResponseException()

    private class ChaosFaultInvalidResponseException :
        RuntimeException("Expected NetworkDelayResponse in response body but got none")
}
