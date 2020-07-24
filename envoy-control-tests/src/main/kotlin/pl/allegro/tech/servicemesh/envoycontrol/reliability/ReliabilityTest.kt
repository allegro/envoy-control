package pl.allegro.tech.servicemesh.envoycontrol.reliability

import com.google.common.base.Strings
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Duration
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlRunnerTestApp
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulOperations
import pl.allegro.tech.servicemesh.envoycontrol.reliability.Toxiproxy.Companion.envoyControl1HttpProxy
import pl.allegro.tech.servicemesh.envoycontrol.reliability.Toxiproxy.Companion.envoyControl1Proxy
import pl.allegro.tech.servicemesh.envoycontrol.reliability.Toxiproxy.Companion.envoyControl2HttpProxy
import pl.allegro.tech.servicemesh.envoycontrol.reliability.Toxiproxy.Companion.envoyControl2Proxy
import pl.allegro.tech.servicemesh.envoycontrol.reliability.Toxiproxy.Companion.externalConsulPort
import pl.allegro.tech.servicemesh.envoycontrol.reliability.Toxiproxy.Companion.externalEnvoyControl1GrpcPort
import pl.allegro.tech.servicemesh.envoycontrol.reliability.Toxiproxy.Companion.toxiproxyGrpcPort
import java.util.concurrent.TimeUnit

@Tag("reliability")
open class ReliabilityTest : EnvoyControlTestConfiguration() {

    companion object {
        @JvmStatic
        @BeforeAll
        fun setup() {
            setup(
                appFactoryForEc1 = {
                    EnvoyControlRunnerTestApp(
                        consulPort = externalConsulPort,
                        grpcPort = toxiproxyGrpcPort
                    )
                },
                envoyConnectGrpcPort = externalEnvoyControl1GrpcPort
            )
        }

        @JvmStatic
        @AfterAll
        fun after() {
            teardown()
            makeEnvoyControlAvailable()
            makeEnvoyControl2Available()
        }

        fun makeEnvoyControlAvailable() {
            envoyControl1Proxy.enable()
            envoyControl1HttpProxy.enable()
        }

        fun makeEnvoyControlUnavailable() {
            envoyControl1Proxy.disable()
            envoyControl1HttpProxy.disable()
        }

        fun makeEnvoyControl2Available() {
            envoyControl2Proxy.enable()
            envoyControl2HttpProxy.enable()
        }

        fun makeEnvoyControl2Unavailable() {
            envoyControl2Proxy.disable()
            envoyControl2HttpProxy.disable()
        }

        fun cutOffConnectionBetweenECs() {
            envoyControl1HttpProxy.disable()
            envoyControl2HttpProxy.disable()
        }

        fun restoreConnectionBetweenECs() {
            envoyControl1HttpProxy.enable()
            envoyControl2HttpProxy.enable()
        }
    }

    @AfterEach
    fun cleanup() {
        ((consulMastersInDc1 + consulMastersInDc2).map {
            it.container
        } + listOf(envoyContainer1, echoContainer, echoContainer2)).forEach {
            it.sigcont()
            it.clearAllIptablesRules()
        }
    }

    fun assertConsulHasNoLeader(consulOperations: ConsulOperations = consulOperationsInFirstDc) {
        untilAsserted {
            assertThat(consulOperations.leader()).isEmpty()
        }
    }

    fun assertConsulHasALeader(consulOperations: ConsulOperations = consulOperationsInFirstDc) {
        untilAsserted {
            assertThat(consulOperations.leader()).isNotEmpty()
        }
    }

    fun assertReachableThroughEnvoy(service: String) {
        untilAsserted {
            assertReachableThroughEnvoyOnce(service)
        }
    }

    fun assertReachableThroughEnvoyOnce(service: String) {
        callService(service).use {
            assertThat(it).isOk().isFrom(echoContainer)
        }
    }

    fun assertUnreachableThroughEnvoy(service: String) {
        untilAsserted {
            assertUnreachableThroughEnvoyOnce(service)
        }
    }

    fun assertUnreachableThroughEnvoyOnce(service: String) {
        callService(service).use {
            assertThat(it).isUnreachable()
        }
    }

    fun holdAssertionsTrue(
        duration: Duration = failureDuration,
        interval: Duration,
        assertion: () -> Unit
    ) {
        val intervalInMs = interval.valueInMS
        val probes = duration.valueInMS / intervalInMs
        runRepeat(probes, intervalInMs, assertion)
    }

    fun holdAssertionsTrue(
        duration: Duration = failureDuration,
        probes: Long = 10L,
        assertion: () -> Unit
    ) {
        val millis = duration.valueInMS
        val interval = millis / probes
        runRepeat(probes, interval, assertion)
    }

    private fun runRepeat(probes: Long, intervalInMs: Long, assertion: () -> Unit) {
        if (probes == 0L) {
            repeatWithSleep(1, intervalInMs) {
                assertion()
            }
        } else {
            repeatWithSleep(probes, intervalInMs) {
                assertion()
            }
        }
    }

    private fun repeatWithSleep(probes: Long, interval: Long, assertion: () -> Unit) {
        repeat(probes.toInt()) {
            assertion()

            if (interval > 0) {
                Thread.sleep(interval)
            }
        }
    }

    val failureDuration = Duration(
        System.getProperty("RELIABILITY_FAILURE_DURATION_SECONDS")
            ?.let { Strings.emptyToNull(it) }
            ?.toLong()
            ?: 20,
        TimeUnit.SECONDS
    )
}
