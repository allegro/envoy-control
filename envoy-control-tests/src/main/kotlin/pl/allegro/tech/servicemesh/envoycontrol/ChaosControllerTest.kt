@file:Suppress("FunctionName")

package pl.allegro.tech.servicemesh.envoycontrol

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import okhttp3.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.http.HttpStatus
import pl.allegro.tech.servicemesh.envoycontrol.chaos.api.ExperimentsListResponse
import pl.allegro.tech.servicemesh.envoycontrol.chaos.api.NetworkDelay
import pl.allegro.tech.servicemesh.envoycontrol.chaos.api.NetworkDelayResponse
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import java.util.UUID

private val sampleNetworkDelayRequest = NetworkDelay(
    affectedService = "sample-affected-service",
    delay = "1m",
    duration = "1s",
    targetService = "sample-target"
)
private val sampleNetworkDelayId = UUID.randomUUID().toString()

internal class ChaosControllerTest {

    private val objectMapper: ObjectMapper = ObjectMapper()
        .registerModule(KotlinModule.Builder().build())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    companion object {
        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(consul)
    }

    @Test
    fun `should return UNAUTHORIZED for invalid user`() {
        // when
        val response = envoyControl.app.postChaosFaultRequest(
            username = "bad-user",
            password = "wrong-pass",
            networkDelay = sampleNetworkDelayRequest
        )

        // then
        assertThat(response.code).isEqualTo(HttpStatus.UNAUTHORIZED.value())
    }

    @Test
    fun `should post a chaos fault request and get response with storage object`() {
        // when
        val response = convertResponseToNetworkDelayResponse(
            envoyControl.app.postChaosFaultRequest(networkDelay = sampleNetworkDelayRequest)
        )

        // then
        with(response) {
            assertThat(id).isNotEmpty()
            assertThat(affectedService).isEqualTo(sampleNetworkDelayRequest.affectedService)
            assertThat(delay).isEqualTo(sampleNetworkDelayRequest.delay)
            assertThat(duration).isEqualTo(sampleNetworkDelayRequest.duration)
            assertThat(targetService).isEqualTo(sampleNetworkDelayRequest.targetService)
        }
    }

    @Test
    fun `should accept delete request and return NO_CONTENT (204)`() {
        // when
        val response = envoyControl.app.deleteChaosFaultRequest(faultId = sampleNetworkDelayId)

        // then
        assertThat(response.code).isEqualTo(HttpStatus.NO_CONTENT.value())
    }

    @Test
    fun `should return experiment list with OK (200) status`() {
        // when
        val response = envoyControl.app.getExperimentsListRequest()

        // then
        assertThat(response.code).isEqualTo(HttpStatus.OK.value())
    }

    @Test
    fun `should return empty experiment list when no experiment is running`() {
        // when
        val response: ExperimentsListResponse = convertResponseToExperimentsListResponse(
            envoyControl.app.getExperimentsListRequest()
        )

        // then
        assertThat(response.experimentList).isEqualTo(emptyList<NetworkDelayResponse>())
    }

    @Test
    fun `should response with list of posted Network Delay`() {
        // given
        removeAllFromStorage()
        val item1 = convertResponseToNetworkDelayResponse(
            envoyControl.app.postChaosFaultRequest(networkDelay = sampleNetworkDelayRequest)
        )
        val item2 = convertResponseToNetworkDelayResponse(
            envoyControl.app.postChaosFaultRequest(networkDelay = sampleNetworkDelayRequest)
        )

        // when
        val itemsList = convertResponseToExperimentsListResponse(
            envoyControl.app.getExperimentsListRequest()
        )

        // then
        with(itemsList.experimentList) {
            assertThat(size).isEqualTo(2)
            assertThat(this.containsAll(listOf(item1, item2)))
        }
    }

    @Test
    fun `should remove correct Network Delay item from storage`() {
        // given
        removeAllFromStorage()
        val item1 = convertResponseToNetworkDelayResponse(
            envoyControl.app.postChaosFaultRequest(networkDelay = sampleNetworkDelayRequest)
        )
        val item2 = convertResponseToNetworkDelayResponse(
            envoyControl.app.postChaosFaultRequest(networkDelay = sampleNetworkDelayRequest)
        )
        val item3 = convertResponseToNetworkDelayResponse(
            envoyControl.app.postChaosFaultRequest(networkDelay = sampleNetworkDelayRequest)
        )
        val itemsList = convertResponseToExperimentsListResponse(
            envoyControl.app.getExperimentsListRequest()
        )
        assertThat(itemsList.experimentList.size).isEqualTo(3)

        // when
        val response = envoyControl.app.deleteChaosFaultRequest(faultId = item2.id)

        // then
        val resultItemsList = convertResponseToExperimentsListResponse(
            envoyControl.app.getExperimentsListRequest()
        )
        with(resultItemsList.experimentList) {
            assertThat(size).isEqualTo(2)
            assertThat(this.containsAll(listOf(item1, item3)))
        }
    }

    private fun removeAllFromStorage() {
        var response = convertResponseToExperimentsListResponse(
            envoyControl.app.getExperimentsListRequest()
        )

        for (item in response.experimentList) {
            envoyControl.app.deleteChaosFaultRequest(faultId = item.id)
        }

        response = convertResponseToExperimentsListResponse(
            envoyControl.app.getExperimentsListRequest()
        )
        assertThat(response.experimentList.size).isEqualTo(0)
    }

    private fun convertResponseToNetworkDelayResponse(response: Response): NetworkDelayResponse =
        response.body
            ?.use { objectMapper.readValue(it.byteStream(), NetworkDelayResponse::class.java) }
            ?: throw ChaosFaultInvalidResponseException()

    private fun convertResponseToExperimentsListResponse(response: Response): ExperimentsListResponse =
        response.body
            ?.use { objectMapper.readValue(it.byteStream(), ExperimentsListResponse::class.java) }
            ?: throw ChaosFaultInvalidResponseException()

    private class ChaosFaultInvalidResponseException :
        RuntimeException("Expected NetworkDelayResponse in response body but got none")
}
