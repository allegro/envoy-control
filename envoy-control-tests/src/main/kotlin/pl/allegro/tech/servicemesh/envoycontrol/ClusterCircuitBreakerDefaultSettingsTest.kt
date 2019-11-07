package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlRunnerTestApp
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.Threshold

internal class ClusterCircuitBreakerDefaultSettingsTest : EnvoyControlTestConfiguration() {

    companion object {
        private val properties = mapOf(
            "envoy-control.envoy.snapshot.egress.commonHttp.circuitBreakers.defaultThreshold" to Threshold("DEFAULT").also {
                it.maxConnections = 1
                it.maxPendingRequests = 2
                it.maxRequests = 3
                it.maxRetries = 4
            },
            "envoy-control.envoy.snapshot.egress.commonHttp.circuitBreakers.highThreshold" to Threshold("HIGH").also {
                it.maxConnections = 5
                it.maxPendingRequests = 6
                it.maxRequests = 7
                it.maxRetries = 8
            }
        )

        @JvmStatic
        @BeforeAll
        fun setupTest() {
            setup(appFactoryForEc1 = { consulPort -> EnvoyControlRunnerTestApp(properties, consulPort) })
        }
    }

    @Test
    fun `should enable setting circuit breaker threstholds setting`() {
        // given
        registerService(name = "echo")
        untilAsserted {
            val response = callEcho()
            assertThat(response).isOk().isFrom(echoContainer)
        }

        // when
        val maxRequestsSetting = envoyContainer1.admin().circuitBreakerSetting("echo", "max_requests", "default_priority")
        val maxRetriesSetting = envoyContainer1.admin().circuitBreakerSetting("echo", "max_retries", "high_priority")

        // then
        assertThat(maxRequestsSetting).isEqualTo(3)
        assertThat(maxRetriesSetting).isEqualTo(8)
    }
}
