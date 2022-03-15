package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isFrom
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isOk
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.Xds
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension
import pl.allegro.tech.servicemesh.envoycontrol.groups.RoutingPriority
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.CircuitBreakerProperties

internal class ClusterCircuitBreakerDefaultSettingsTest {

    companion object {
        private val properties = mapOf(
            "envoy-control.envoy.snapshot.egress.commonHttp.circuitBreakers.defaultThreshold" to CircuitBreakerProperties(RoutingPriority.DEFAULT).also {
                it.maxConnections = 1
                it.maxPendingRequests = 2
                it.maxRequests = 3
                it.maxRetries = 4
            },
            "envoy-control.envoy.snapshot.egress.commonHttp.circuitBreakers.highThreshold" to CircuitBreakerProperties(RoutingPriority.HIGH).also {
                it.maxConnections = 5
                it.maxPendingRequests = 6
                it.maxRequests = 7
                it.maxRetries = 8
                it.retryBudget = null
            }
        )

        private val CIRCUIT_BREAKERS_SETTINGS_CONFIG = """
node:
  metadata:
    proxy_settings:
      outgoing:
        dependencies:
          - service: "echo"
            circuitBreakers:
              defaultThreshold:
                maxRequests: 22
                maxRetries: 10
                retryBudget:
                    minRetryConcurrency: 6
          - service: "echo2"
            circuitBreakers:
              defaultThreshold:
                maxRequests: 22
                maxRetries: 10
                retryBudget:
                    minRetryConcurrency: 6
              highThreshold:
                maxConnections: 11
                maxPendingRequests: 12
                maxRequests: 22
                maxRetries: 10      
            """.trimIndent()

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

        @JvmField
        @RegisterExtension
        val service2 = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val envoy2 = EnvoyExtension(envoyControl, service2, Xds.copy(serviceName = "echo2", configOverride = CIRCUIT_BREAKERS_SETTINGS_CONFIG))
    }

    @Test
    fun `should enable set default circuit breaker threstholds setting`() {
        // given
        consul.server.operations.registerService(name = "echo", extension = service)
        untilAsserted {
            val response = envoy.egressOperations.callService("echo")
            assertThat(response).isOk().isFrom(service)
        }

        // when
        val admin = envoy.container.admin()
        val maxRequestsSetting = admin.circuitBreakerSetting("echo", "max_requests", "default_priority")
        val maxRetriesSetting = admin.circuitBreakerSetting("echo", "max_retries", "high_priority")

        // then
        assertThat(maxRequestsSetting).isEqualTo(3)
        assertThat(maxRetriesSetting).isEqualTo(8)
    }

    @Test
    fun `should set circuit breaker thresholds settings from metadata`() {
        // given
        consul.server.operations.registerService(name = "echo2", extension = service2)

        untilAsserted {
            val response = envoy2.egressOperations.callService("echo2")
            assertThat(response).isOk().isFrom(service2)
        }

        // when
        val admin = envoy2.container.admin()
        val maxRequestSetting = admin.circuitBreakerSetting("echo2", "max_requests", "high_priority")
        val maxConnectionsSetting = admin.circuitBreakerSetting("echo2", "max_connections", "high_priority")
        val maxPendingSetting = admin.circuitBreakerSetting("echo2", "max_pending_requests", "high_priority")
        val maxRetriesSetting = admin.circuitBreakerSetting("echo2", "max_retries", "high_priority")
        val maxRetriesDefaultSetting = admin.circuitBreakerSetting("echo2", "max_retries", "default_priority")

        // then
        assertThat(maxRetriesSetting).isEqualTo(10)
        assertThat(maxRetriesDefaultSetting).isEqualTo(6)
        assertThat(maxConnectionsSetting).isEqualTo(11)
        assertThat(maxPendingSetting).isEqualTo(12)
        assertThat(maxRequestSetting).isEqualTo(22)
    }
}
