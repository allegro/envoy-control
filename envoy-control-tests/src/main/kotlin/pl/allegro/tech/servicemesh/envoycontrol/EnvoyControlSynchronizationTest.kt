package pl.allegro.tech.servicemesh.envoycontrol

import com.codahale.metrics.Timer
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlRunnerTestApp
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration
import pl.allegro.tech.servicemesh.envoycontrol.config.echo.EchoContainer
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

internal class EnvoyControlSynchronizationRunnerTest : EnvoyControlSynchronizationTest() {
    override val pollingInterval: Duration = Companion.pollingInterval
    override val stateSampleDuration: Duration = Companion.stateSampleDuration

    companion object {
        val pollingInterval = Duration.ofSeconds(1)
        val stateSampleDuration = Duration.ofSeconds(1)

        @BeforeAll
        @JvmStatic
        fun setupTest() {
            val properties = mapOf(
                "envoy-control.envoy.snapshot.stateSampleDuration" to stateSampleDuration,
                "envoy-control.sync.enabled" to true,
                "envoy-control.sync.polling-interval" to pollingInterval.seconds
            )
            setup(
                envoyControls = 2,
                appFactoryForEc1 = { consulPort -> EnvoyControlRunnerTestApp(properties, consulPort) }
            )
        }
    }
}

abstract class EnvoyControlSynchronizationTest : EnvoyControlTestConfiguration() {

    abstract val pollingInterval: Duration
    abstract val stateSampleDuration: Duration

    private val logger by logger()

    @Test
    fun `should prefer services from local dc and fallback to remote dc when needed`() {
        // given: local and remote instances
        registerServiceInRemoteCluster("echo", echoContainer2)
        val localId = registerServiceInLocalDc("echo")

        // then: local called
        waitUntilEchoCalledThroughEnvoyResponds(echoContainer)

        // when: no local instances
        deregisterService(localId)

        // then: remote called
        waitUntilEchoCalledThroughEnvoyResponds(echoContainer2)

        // when: local instances again
        registerServiceInLocalDc("echo")

        // then: local called
        waitUntilEchoCalledThroughEnvoyResponds(echoContainer)
    }

    @Test
    fun `latency between service registration in remote dc and being able to access it via envoy should be similar to envoy-control polling interval`() {
        // when
        val latency = measureRegistrationToAccessLatency { name, target ->
            registerServiceInRemoteCluster(name, target)
        }

        // then
        logger.info("remote dc latency: $latency")

        val tolerance = Duration.ofMillis(400) + stateSampleDuration
        val expectedMax = (pollingInterval + tolerance).toMillis()
        assertThat(latency.max()).isLessThanOrEqualTo(expectedMax)
    }

    @Test
    fun `latency between service registration in local dc and being able to access it via envoy should be less than 0,5s + stateSampleDuration`() {
        // when
        val latency = measureRegistrationToAccessLatency { name, target ->
            registerServiceInLocalDc(name, target)
        }

        // then
        logger.info("local dc latency: $latency")

        assertThat(latency.max()).isLessThanOrEqualTo(500 + stateSampleDuration.toMillis())
    }

    private fun measureRegistrationToAccessLatency(registerService: (String, EchoContainer) -> Unit): LatencySummary {
        val timer = Timer()

        // when
        for (i in 1..5) {
            val serviceName = "service-$i"
            registerService.invoke(serviceName, echoContainer)

            timer.time {
                await()
                    .pollDelay(50, TimeUnit.MILLISECONDS)
                    .atMost(defaultDuration)
                    .untilAsserted {
                        // when
                        val response = callService(serviceName)

                        // then
                        assertThat(response).isOk().isFrom(echoContainer)
                    }
            }
        }
        return LatencySummary(timer)
    }

    private fun registerServiceInLocalDc(name: String, target: EchoContainer = echoContainer): String =
        registerService(UUID.randomUUID().toString(), name, target)

    private class LatencySummary(private val timer: Timer) {

        private fun nanosToMillis(nanos: Long) = Duration.ofNanos(nanos).toMillis()

        fun max(): Long = nanosToMillis(timer.snapshot.max)

        override fun toString() = "LatencySummary(" +
            "max = ${nanosToMillis(timer.snapshot.max)} ms, " +
            "median = ${nanosToMillis(timer.snapshot.median.toLong())} ms" +
            ")"
    }
}
