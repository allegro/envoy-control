package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isFrom
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isOk
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.Threshold

internal class ClusterCircuitBreakerDefaultSettingsTest {

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
                it.trackRemaining = true
            }
        )

        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(consul, properties)

        @JvmField
        @RegisterExtension
        val service = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val envoy = EnvoyExtension(envoyControl, service)
    }

    @Test
    fun `should enable setting circuit breaker threstholds setting`() {
        // given
        consul.server.operations.registerService(name = "echo", extension = service)
        untilAsserted {
            val response = envoy.egressOperations.callService("echo")
            assertThat(response).isOk().isFrom(service)
        }

        // when
        val maxRequestsSetting = envoy.container.admin().circuitBreakerSetting("echo", "max_requests", "default_priority")
        val maxRetriesSetting = envoy.container.admin().circuitBreakerSetting("echo", "max_retries", "high_priority")
        val remainingPendingMetric = envoy.container.admin().statValue("cluster.echo.circuit_breakers.default.remaining_pending")
        val remainingRqMetric = envoy.container.admin().statValue("cluster.echo.circuit_breakers.default.remaining_rq")

        // then
        assertThat(maxRequestsSetting).isEqualTo(3)
        assertThat(maxRetriesSetting).isEqualTo(8)
        assertThat(remainingPendingMetric).isNotNull()
        assertThat(remainingRqMetric).isNotNull()
    }
}
