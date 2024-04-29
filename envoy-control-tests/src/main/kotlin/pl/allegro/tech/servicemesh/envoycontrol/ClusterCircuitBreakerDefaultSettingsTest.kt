package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
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
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.stream.IntStream

internal class ClusterCircuitBreakerDefaultSettingsTest {

    companion object {
        const val maxPending = 2
        private val properties = mapOf(
            "envoy-control.envoy.snapshot.egress.commonHttp.circuitBreakers.defaultThreshold" to Threshold("DEFAULT").also {
                it.maxConnections = 1
                it.maxPendingRequests = maxPending
                it.maxRequests = 1
                it.maxRetries = 4
            },
            "envoy-control.envoy.snapshot.egress.commonHttp.circuitBreakers.highThreshold" to Threshold("HIGH").also {
                it.maxConnections = 5
                it.maxPendingRequests = 6
                it.maxRequests = 7
                it.maxRetries = 8
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

        @JvmField
        @RegisterExtension
        val envoy2 = EnvoyExtension(envoyControl)
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
        val maxRequestsSetting =
            envoy.container.admin().circuitBreakerSetting("echo", "max_requests", "default_priority")
        val maxRetriesSetting = envoy.container.admin().circuitBreakerSetting("echo", "max_retries", "high_priority")
        val remainingPendingMetric =
            envoy.container.admin().statValue("cluster.echo.circuit_breakers.default.remaining_pending")
        val remainingRqMetric = envoy.container.admin().statValue("cluster.echo.circuit_breakers.default.remaining_rq")

        // then
        assertThat(maxRequestsSetting).isEqualTo(1)
        assertThat(maxRetriesSetting).isEqualTo(8)
        assertThat(remainingPendingMetric).isNotNull()
        assertThat(remainingRqMetric).isNotNull()
    }

    @Tag("flaky")
    @Test
    fun `should have decreased remaining pending rq`() {
        consul.server.operations.registerServiceWithEnvoyOnIngress(name = "echo", extension = envoy)
        untilAsserted {
            val response = envoy.egressOperations.callService("echo")
            assertThat(response).isOk().isFrom(service)
        }
        val latch = CountDownLatch(1)
        val callTask = Callable {
            envoy2.egressOperations.callService("echo")
            true
        }
        val checkTask = Callable {
            if (pendingRqLessThan(maxPending)) {
                latch.countDown()
            }
            true
        }
        val rqNum = 10
        val callableTasks: ArrayList<Callable<Boolean>> = ArrayList()
        IntStream.range(0, rqNum).forEach {
            callableTasks.add(callTask)
            callableTasks.add(checkTask)
        }

        val executor = Executors.newFixedThreadPool(2 * rqNum)
        executor.invokeAll(callableTasks)
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue()
        executor.shutdown()
    }

    private fun pendingRqLessThan(value: Int) =
        envoy2.container.admin().statValue("cluster.echo.circuit_breakers.default.remaining_pending")
            ?.let { it.toIntOrNull() != value } ?: false
}
