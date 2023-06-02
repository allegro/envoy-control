package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isEitherFrom
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isFrom
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isOk
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulClusterSetup
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulMultiClusterExtension
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
            "envoy-control.envoy.snapshot.loadBalancing.priorities.dcPriorityProperties.dc1" to 0,
            "envoy-control.envoy.snapshot.loadBalancing.priorities.dcPriorityProperties.dc2" to 1,
            "envoy-control.envoy.snapshot.loadBalancing.priorities.dcPriorityProperties.dc3" to 2
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
        val envoy = EnvoyExtension(envoyControl)

        @JvmField
        @RegisterExtension
        val serviceDC1_1 = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val serviceDC1_2 = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val serviceDC1_3 = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val serviceDC1_4 = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val serviceDC2_1 = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val serviceDC2_2 = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val serviceDC2_3 = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val serviceDC2_4 = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val serviceDC3_1 = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val serviceDC3_2 = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val serviceDC3_3 = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val serviceDC3_4 = EchoServiceExtension()
    }

    @Test
    fun `should route traffic to local dc when all instances are healthy`() {
        consulClusters.serverFirst.registerService(serviceDC1_1)
        consulClusters.serverSecond.registerService(serviceDC2_1)
        consulClusters.serverThird.registerService(serviceDC3_1)
        waitServiceOkAndFrom(serviceDC1_1)
    }

    @Test
    fun `should route traffic to dc2 when there are no healthy instances in dc1`() {
        val registeredId1 = consulClusters.serverFirst.registerService(serviceDC1_1)
        consulClusters.serverSecond.registerService(serviceDC2_1)
        consulClusters.serverThird.registerService(serviceDC3_1)
        consulClusters.serverFirst.operations.deregisterService(registeredId1)

        waitServiceOkAndFrom(serviceDC2_1)
        consulClusters.serverFirst.reRegisterServiceAndVerifyCall(serviceDC1_1)
    }

    @Test
    fun `should route traffic to dc3 when there are no healthy instances in others`() {
        val registeredId1 = consulClusters.serverFirst.registerService(serviceDC1_1)
        val registeredId2 = consulClusters.serverSecond.registerService(serviceDC2_1)
        consulClusters.serverThird.registerService(serviceDC3_1)
        consulClusters.serverFirst.operations.deregisterService(registeredId1)
        consulClusters.serverFirst.operations.deregisterService(registeredId2)

        waitServiceOkAndFrom(serviceDC3_1)
        consulClusters.serverFirst.reRegisterServiceAndVerifyCall(serviceDC2_1)
        consulClusters.serverFirst.reRegisterServiceAndVerifyCall(serviceDC1_1)
    }

    @Test
    fun `should not route traffic to dc3 when there are 50 percent healthy instances in others`() {
        val dc1Ids = listOf(serviceDC1_1, serviceDC1_2, serviceDC1_3, serviceDC1_4)
            .register(consulClusters.serverFirst)
        val dc2Ids = listOf(serviceDC2_1, serviceDC2_2, serviceDC2_3, serviceDC2_4)
            .register(consulClusters.serverSecond)
        listOf(serviceDC3_1, serviceDC3_2, serviceDC3_3, serviceDC3_4).register(consulClusters.serverThird)

        val halfOfInstances = dc1Ids.size / 2
        dc1Ids.take(halfOfInstances).forEach { consulClusters.serverFirst.operations.deregisterService(it) }
        dc2Ids.take(halfOfInstances).forEach { consulClusters.serverSecond.operations.deregisterService(it) }

        untilAsserted {
            envoy.egressOperations.callService(serviceName).also {
                assertThat(it).isOk().isEitherFrom(serviceDC1_3, serviceDC1_4, serviceDC2_3, serviceDC2_4)
            }
        }
    }

    @Test
    fun `should not route traffic to dc3 when there are 25 and 100 percents healthy instances in others`() {
        val dc1Ids = listOf(serviceDC1_1, serviceDC1_2, serviceDC1_3, serviceDC1_4).register(consulClusters.serverFirst)
        listOf(serviceDC2_1, serviceDC2_2, serviceDC2_3, serviceDC2_4).register(consulClusters.serverSecond)
        listOf(serviceDC3_1, serviceDC3_2, serviceDC3_3, serviceDC3_4).register(consulClusters.serverThird)

        dc1Ids.take(3).forEach { consulClusters.serverFirst.operations.deregisterService(it) }

        untilAsserted {
            envoy.egressOperations.callService(serviceName).also {
                assertThat(it).isOk()
                    .isEitherFrom(serviceDC1_4, serviceDC2_1, serviceDC2_2, serviceDC2_3, serviceDC2_4)
            }
        }
    }

    private fun waitServiceOkAndFrom(echoServiceExtension: EchoServiceExtension) {
        untilAsserted {
            envoy.egressOperations.callService(serviceName).also {
                assertThat(it).isOk().isFrom(echoServiceExtension)
            }
        }
    }

    private fun ConsulClusterSetup.registerService(service: EchoServiceExtension): String =
        this.operations.registerService(service, name = serviceName)

    private fun List<EchoServiceExtension>.register(consulCluster: ConsulClusterSetup) =
        this.map { consulCluster.registerService(it) }.toList()

    private fun ConsulClusterSetup.reRegisterServiceAndVerifyCall(service: EchoServiceExtension) {
        this.operations.registerService(service, name = serviceName)
        waitServiceOkAndFrom(service)
    }
}
