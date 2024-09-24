package pl.allegro.tech.servicemesh.envoycontrol

import com.codahale.metrics.Timer
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isFrom
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isOk
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulMultiClusterExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlClusteredExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension
import java.time.Duration
import java.util.concurrent.TimeUnit

internal class EnvoyControlSynchronizationTest {

    private val logger by logger()

    companion object {
        val pollingInterval = Duration.ofSeconds(1)
        val stateSampleDuration = Duration.ofSeconds(1)
        val defaultDuration: Duration = Duration.ofSeconds(90)

        @JvmField
        @RegisterExtension
        val consulClusters = ConsulMultiClusterExtension()

        val properties = mapOf(
            "envoy-control.envoy.snapshot.stateSampleDuration" to stateSampleDuration,
            "envoy-control.sync.enabled" to true,
            "envoy-control.sync.blackListedRemoteClusters" to setOf("dc3"),
            "envoy-control.sync.polling-interval" to pollingInterval.seconds
        )

        @JvmField
        @RegisterExtension
        val envoyControlDc1 = EnvoyControlClusteredExtension(consulClusters.serverFirst, { properties }, listOf(consulClusters))

        @JvmField
        @RegisterExtension
        val envoyControlDc2 = EnvoyControlClusteredExtension(consulClusters.serverSecond, dependencies = listOf(consulClusters))
        @JvmField
        @RegisterExtension
        val envoyControlDc3 = EnvoyControlClusteredExtension(consulClusters.serverThird, dependencies = listOf(consulClusters))

        @JvmField
        @RegisterExtension
        val envoy = EnvoyExtension(envoyControlDc1)

        @JvmField
        @RegisterExtension
        val serviceLocal = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val serviceRemote = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val serviceRemote3 = EchoServiceExtension()
    }

    @Test
    fun `should prefer services from local dc and fallback to remote dc when needed`() {

        // given: local and remote instances
        registerServiceInRemoteDc2("echo", serviceRemote)
        val serviceId = registerServiceInLocalDc("echo", serviceLocal)

        // then: local called
        waitServiceOkAndFrom("echo", serviceLocal)

        // when: no local instances
        deregisterService(serviceId)

        // then: remote called
        waitServiceOkAndFrom("echo", serviceRemote)

        // when: local instances again
        registerServiceInLocalDc("echo", serviceLocal)

        // then: local called
        waitServiceOkAndFrom("echo", serviceLocal)
    }


    @Test
    fun `should not synchronize blacklisted remote clusters`() {

        // given: local and remote instances
        registerServiceInRemoteDc3("echo", serviceRemote3)
        registerServiceInRemoteDc2("echo", serviceRemote)

        // when: dc2 is synchronized
        envoy.waitForClusterEndpointHealthy("echo", serviceRemote.container().ipAddress())

        // when: instances from dc3 are absent
        envoy.waitForClusterEndpointNotHealthy("echo", serviceRemote3.container().ipAddress())


    }
    @Test
    fun `latency between service registration in local dc and being able to access it via envoy should be less than 0,5s + stateSampleDuration`() {
        // when
        val latency = measureRegistrationToAccessLatency(
            registerService = { name, target -> registerServiceInLocalDc(name, target) },
            readinessCheck = { name, target -> waitServiceOkAndFrom(name, target) }
        )

        // then
        logger.info("local dc latency: $latency")

        assertThat(latency.max()).isLessThanOrEqualTo(500 + stateSampleDuration.toMillis())
    }

    private fun measureRegistrationToAccessLatency(
        registerService: (String, echoServiceExtension: EchoServiceExtension) -> Unit,
        readinessCheck: (name: String, echoServiceExtension: EchoServiceExtension) -> Unit
    ): LatencySummary {
        val timer = Timer()

        // when
        for (i in 1..5) {
            val serviceName = "service-$i"
            registerService.invoke(serviceName, serviceLocal)

            timer.time {
                Awaitility.await()
                    .pollDelay(50, TimeUnit.MILLISECONDS)
                    .atMost(defaultDuration)
                    .untilAsserted {
                        readinessCheck.invoke(serviceName, serviceLocal)
                    }
            }
        }
        return LatencySummary(timer)
    }

    private fun deregisterService(serviceId: String) {
        consulClusters.serverFirst.operations.deregisterService(serviceId)
    }

    private fun waitServiceOkAndFrom(name: String, echoServiceExtension: EchoServiceExtension) {
        untilAsserted {
            envoy.egressOperations.callService(name).also {
                assertThat(it).isOk().isFrom(echoServiceExtension)
            }
        }
    }

    private fun waitServiceUnhealthy(name: String, echoServiceExtension: EchoServiceExtension) {
        untilAsserted {
            envoy.waitForClusterEndpointNotHealthy(name, echoServiceExtension.container().ipAddress())
        }
    }

    fun registerServiceInLocalDc(name: String, target: EchoServiceExtension): String {
        return consulClusters.serverFirst.operations.registerService(target, name = name)
    }

    fun registerServiceInRemoteDc2(name: String, target: EchoServiceExtension): String {
        return consulClusters.serverSecond.operations.registerService(target, name = name)
    }

    fun registerServiceInRemoteDc3(name: String, target: EchoServiceExtension): String {
        return consulClusters.serverThird.operations.registerService(target, name = name)
    }

    private class LatencySummary(private val timer: Timer) {

        private fun nanosToMillis(nanos: Long) = Duration.ofNanos(nanos).toMillis()

        fun max(): Long = nanosToMillis(timer.snapshot.max)

        override fun toString() = "LatencySummary(" +
            "max = ${nanosToMillis(timer.snapshot.max)} ms, " +
            "median = ${nanosToMillis(timer.snapshot.median.toLong())} ms" +
            ")"
    }
}
