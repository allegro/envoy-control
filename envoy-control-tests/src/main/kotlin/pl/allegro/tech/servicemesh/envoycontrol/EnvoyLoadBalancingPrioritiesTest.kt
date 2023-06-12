package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isFrom
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isOk
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulClusterSetup
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulMultiClusterExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.CallStats
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlClusteredExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension
import java.time.Duration

class EnvoyLoadBalancingPrioritiesTest {
    companion object {
        private const val serviceName = "echo"
        private val pollingInterval = Duration.ofSeconds(1)
        private val stateSampleDuration = Duration.ofSeconds(1)
        private val properties = mapOf(
            "envoy-control.envoy.snapshot.stateSampleDuration" to stateSampleDuration,
            "envoy-control.sync.enabled" to true,
            "envoy-control.sync.polling-interval" to pollingInterval.seconds,
            "envoy-control.envoy.snapshot.loadBalancing.priorities.zonePriorities" to mapOf(
                "dc1" to 1,
                "dc2" to 1,
                "dc3" to 2
            )
        )

        @JvmField
        @RegisterExtension
        val consulClusters = ConsulMultiClusterExtension()

        @JvmField
        @RegisterExtension
        val envoyControl =
            EnvoyControlClusteredExtension(consulClusters.serverFirst, { properties }, listOf(consulClusters))

        @JvmField
        @RegisterExtension
        val envoyControl2 =
            EnvoyControlClusteredExtension(consulClusters.serverSecond, { properties }, listOf(consulClusters))

        @JvmField
        @RegisterExtension
        val envoyControl3 =
            EnvoyControlClusteredExtension(consulClusters.serverThird, { properties }, listOf(consulClusters))

        @JvmField
        @RegisterExtension
        val envoyDC1 = EnvoyExtension(envoyControl)

        @JvmField
        @RegisterExtension
        val envoyDC2 = EnvoyExtension(envoyControl2)

        @JvmField
        @RegisterExtension
        val envoyDC3 = EnvoyExtension(envoyControl3)

        @JvmField
        @RegisterExtension
        val serviceDC1_1 = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val serviceDC2_1 = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val serviceDC3_1 = EchoServiceExtension()
    }

    @Test
    fun `should route traffic to local dc when all instances are healthy`() {
        consulClusters.serverFirst.registerService(serviceDC1_1)
        consulClusters.serverSecond.registerService(serviceDC2_1)
        consulClusters.serverThird.registerService(serviceDC3_1)
        waitServiceOkAndFrom(serviceDC1_1)

        envoyDC1.callEchoServiceRepeatedly(serviceDC1_1, serviceDC2_1, serviceDC3_1)
            .verifyNoCallsRoutedTo(serviceDC2_1, serviceDC3_1)

        envoyDC2.callEchoServiceRepeatedly(serviceDC1_1, serviceDC2_1, serviceDC3_1)
            .verifyNoCallsRoutedTo(serviceDC1_1, serviceDC3_1)

        envoyDC3.callEchoServiceRepeatedly(serviceDC1_1, serviceDC2_1, serviceDC3_1)
            .verifyNoCallsRoutedTo(serviceDC1_1, serviceDC2_1)
    }

    @Test
    fun `should route traffic to dc2 when there are no healthy instances in dc1`() {
        consulClusters.serverSecond.registerServiceAndVerifyCall(serviceDC2_1)
        consulClusters.serverThird.registerService(serviceDC3_1)

        envoyDC1.callEchoServiceRepeatedly(serviceDC2_1, serviceDC3_1)
            .verifyNoCallsRoutedTo(serviceDC3_1)

        consulClusters.serverFirst.registerServiceAndVerifyCall(serviceDC1_1)
        envoyDC1.callEchoServiceRepeatedly(serviceDC1_1, serviceDC2_1, serviceDC3_1)
            .verifyNoCallsRoutedTo(serviceDC2_1, serviceDC3_1)
    }

    @Test
    fun `should route traffic to dc3 only when there are no healthy instances in others`() {
        consulClusters.serverThird.registerServiceAndVerifyCall(serviceDC3_1)

        consulClusters.serverFirst.registerServiceAndVerifyCall(serviceDC2_1)
        envoyDC1.callEchoServiceRepeatedly(serviceDC2_1, serviceDC3_1)
            .verifyNoCallsRoutedTo(serviceDC3_1)

        consulClusters.serverFirst.registerServiceAndVerifyCall(serviceDC1_1)
        envoyDC1.callEchoServiceRepeatedly(serviceDC1_1, serviceDC2_1, serviceDC3_1)
            .verifyNoCallsRoutedTo(serviceDC3_1)
    }

    private fun waitServiceOkAndFrom(echoServiceExtension: EchoServiceExtension) {
        untilAsserted {
            envoyDC1.egressOperations.callService(serviceName).also {
                assertThat(it).isOk().isFrom(echoServiceExtension)
            }
        }
    }

    private fun EnvoyExtension.callEchoServiceRepeatedly(
        vararg services: EchoServiceExtension
    ): CallStats {
        val stats = CallStats(services.asList())
        this.egressOperations.callServiceRepeatedly(
            service = serviceName,
            stats = stats,
            minRepeat = 50,
            maxRepeat = 50,
            repeatUntil = { true },
            headers = mapOf()
        )
        return stats
    }

    private fun ConsulClusterSetup.registerService(service: EchoServiceExtension): String =
        this.operations.registerService(service, name = serviceName)

    private fun ConsulClusterSetup.registerServiceAndVerifyCall(service: EchoServiceExtension) {
        this.operations.registerService(service, name = serviceName)
        waitServiceOkAndFrom(service)
    }

    private fun CallStats.verifyNoCallsRoutedTo(vararg services: EchoServiceExtension) =
        services.forEach {
            assertThat(this.hits(it)).isEqualTo(0)
        }
}
