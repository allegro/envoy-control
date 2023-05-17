package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions
import org.awaitility.Awaitility
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlRunnerTestApp
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlTestApp
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.stream.Stream

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class StateControllerTest {
    private val logger by logger()

    companion object {
        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        private val regularProperties = mapOf(
            "envoy-control.sync.enabled" to true,
            "envoy-control.envoy.snapshot.routing.service-tags.enabled" to true,
            "envoy-control.envoy.snapshot.routing.service-tags.metadata-key" to "tag",
            "envoy-control.envoy.snapshot.routing.service-tags.auto-service-tag-enabled" to true,
            "envoy-control.envoy.snapshot.routing.service-tags.reject-requests-with-duplicated-auto-service-tag" to true
        )

        private val gzipEnabledProperties = mapOf(
            "envoy-control.sync.gzip.enabled" to true,
            "envoy-control.sync.enabled" to true,
            "envoy-control.envoy.snapshot.routing.service-tags.enabled" to true,
            "envoy-control.envoy.snapshot.routing.service-tags.metadata-key" to "tag",
            "envoy-control.envoy.snapshot.routing.service-tags.auto-service-tag-enabled" to true,
            "envoy-control.envoy.snapshot.routing.service-tags.reject-requests-with-duplicated-auto-service-tag" to true
        )

        @JvmStatic
        fun provideProperties(): Stream<Map<String, Any>> = Stream.of(
            regularProperties, gzipEnabledProperties
        )
    }

    @ParameterizedTest
    @MethodSource(value = ["provideProperties"])
    fun `should return state`(properties: Map<String, Any>) {
        logger.info("Running test with parameters: $properties")
        val app = EnvoyControlRunnerTestApp(
            propertiesProvider = { properties },
            consulPort = consul.server.port
        )
        app.run()
        waitUntilHealthy(app)
        untilAsserted(wait = Duration.ofSeconds(5)) {
            val state = app.getState()
            Assertions.assertThat(state)
                .isNotNull()
                .hasNoNullFieldsOrProperties()
        }
        app.stop()
    }

    private fun waitUntilHealthy(app: EnvoyControlTestApp) {
        Awaitility.await().atMost(30, TimeUnit.SECONDS).untilAsserted {
            Assertions.assertThat(app.isHealthy()).isTrue()
        }
    }
}
