package pl.allegro.tech.servicemesh.envoycontrol

import okhttp3.Request
import okhttp3.Response
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType.APPLICATION_JSON_VALUE
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.ClientsFactory
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.HttpResponseCloser.addToCloseableResponses
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlRunnerTestApp
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlTestApp
import pl.allegro.tech.servicemesh.envoycontrol.services.ServicesState
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class StateControllerTest {
    companion object {
        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        @JvmStatic
        private fun regularProperties() = mapOf(
            "envoy-control.sync.enabled" to true,
            "envoy-control.envoy.snapshot.routing.service-tags.enabled" to true,
            "envoy-control.envoy.snapshot.routing.service-tags.metadata-key" to "tag",
            "envoy-control.envoy.snapshot.routing.service-tags.auto-service-tag-enabled" to true,
            "envoy-control.envoy.snapshot.routing.service-tags.reject-requests-with-duplicated-auto-service-tag" to true
        )

        @JvmStatic
        private fun gzipEnabledProperties() = mapOf(
            "server.compression.enabled" to true,
            "server.compression.mime-types" to
                "application/json,application/xml,text/html,text/xml,text/plain,application/javascript,text/css",
            "server.compression.min-response-size" to "1",
            "envoy-control.sync.enabled" to true,
            "envoy-control.envoy.snapshot.routing.service-tags.enabled" to true,
            "envoy-control.envoy.snapshot.routing.service-tags.metadata-key" to "tag",
            "envoy-control.envoy.snapshot.routing.service-tags.auto-service-tag-enabled" to true,
            "envoy-control.envoy.snapshot.routing.service-tags.reject-requests-with-duplicated-auto-service-tag" to true
        )
    }

    @Test
    fun `should return json state`() {
        val app = EnvoyControlRunnerTestApp(
            propertiesProvider = { regularProperties() },
            consulPort = consul.server.port
        )
        app.run()

        waitUntilHealthy(app)
        untilAsserted(wait = Duration.ofSeconds(5)) {
            val stateResponse = getState(app.appPort)
            assertThat(stateResponse.body)
                .hasFieldOrPropertyWithValue("contentTypeString", APPLICATION_JSON_VALUE)
            val converted =
                app.objectMapper.convertValue(stateResponse.body?.byteStream(), ServicesState::class.java)
            assertThat(converted)
                .isNotNull()
                .hasNoNullFieldsOrProperties()
        }
        app.stop()
    }

    @Test
    fun `should return gzip state`() {
        val app = buildEnvoyAppWithProperties(gzipEnabledProperties())
        app.run()
        waitUntilHealthy(app)
        untilAsserted(wait = Duration.ofSeconds(5)) {
            val stateResponse = getState(app.appPort)
            val unGzippedStr = GZIPInputStream(stateResponse.body?.byteStream())
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
            val converted = app.objectMapper.readValue(unGzippedStr, ServicesState::class.java)
            assertThat(converted)
                .isNotNull()
                .hasNoNullFieldsOrProperties()
        }
        app.stop()
    }

    private fun buildEnvoyAppWithProperties(properties: Map<String, Any>) =
        EnvoyControlRunnerTestApp(
            propertiesProvider = { properties },
            consulPort = consul.server.port
        )

    private fun getState(appPort: Int): Response {
        return ClientsFactory.createClient()
            .newBuilder().build()
            .newCall(
                Request.Builder()
                    .get()
                    .url("http://localhost:$appPort/state")
                    .addHeader(HttpHeaders.ACCEPT_ENCODING, "gzip")
                    .build()
            )
            .execute().addToCloseableResponses()
    }

    private fun waitUntilHealthy(app: EnvoyControlTestApp) {
        Awaitility.await().atMost(30, TimeUnit.SECONDS).untilAsserted {
            assertThat(app.isHealthy()).isTrue()
        }
    }
}
