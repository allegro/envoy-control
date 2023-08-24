package pl.allegro.tech.servicemesh.envoycontrol.trafficsplitting

import TrafficSplittingConstants.serviceName
import TrafficSplittingConstants.upstreamServiceName
import callUpstreamServiceRepeatedly
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.Xds
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulClusterSetup
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulMultiClusterExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlClusteredExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension
import verifyCallsCountCloseTo
import verifyIsReachable
import verifyNoCalls
import java.time.Duration

class AggregateClusterFallbackTest {
    companion object {
        private const val numberOfCalls = 100
        private const val forceTrafficZone = "dc3"
        private val properties = mapOf(
            "envoy-control.envoy.snapshot.stateSampleDuration" to Duration.ofSeconds(0),
            "envoy-control.sync.enabled" to true,
            "envoy-control.envoy.snapshot.loadBalancing.trafficSplitting.zoneName" to forceTrafficZone,
            "envoy-control.envoy.snapshot.loadBalancing.trafficSplitting.serviceByWeightsProperties"
                to mutableMapOf(serviceName to mutableMapOf("main" to 90, "secondary" to 10)),
            "envoy-control.envoy.snapshot.loadBalancing.priorities.zonePriorities" to mapOf(
                "dc1" to mapOf(
                    "dc1" to 0,
                    "dc2" to 1,
                    "dc3" to 1
                ),
                "dc2" to mapOf(
                    "dc1" to 1,
                    "dc2" to 0,
                    "dc3" to 1,
                ),
                "dc3" to mapOf(
                    "dc1" to 1,
                    "dc2" to 2,
                    "dc3" to 0,
                ),
            )
        )

        private val echo2Config = """
            node:
              metadata:
                proxy_settings:
                  outgoing:
                    dependencies:
                      - service: "service-1"
        """.trimIndent()

        private val config = Xds.copy(configOverride = echo2Config, serviceName = "echo2")

        @JvmField
        @RegisterExtension
        val consul = ConsulMultiClusterExtension()

        @JvmField
        @RegisterExtension
        val envoyControl =
            EnvoyControlClusteredExtension(consul.serverFirst, { properties }, listOf(consul))

        @JvmField
        @RegisterExtension
        val envoyControl2 =
            EnvoyControlClusteredExtension(consul.serverSecond, { properties }, listOf(consul))

        @JvmField
        @RegisterExtension
        val envoyControl3 =
            EnvoyControlClusteredExtension(consul.serverThird, { properties }, listOf(consul))


        @JvmField
        @RegisterExtension
        val upstreamServiceDC1 = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val upstreamServiceDC2 = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val upstreamServiceDC3 = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val envoyDC1 = EnvoyExtension(envoyControl)

        @JvmField
        @RegisterExtension
        val envoyDC2 = EnvoyExtension(envoyControl2)

        @JvmField
        @RegisterExtension
        val envoyDC3 = EnvoyExtension(envoyControl3)
    }

    @Test
    fun `should fallback aсcording to priorities`() {

        consul.serverFirst.operations.registerService(upstreamServiceDC1, name = upstreamServiceName)
        envoyDC1.verifyIsReachable(upstreamServiceDC1, upstreamServiceName)

        consul.serverSecond.operations.registerService(upstreamServiceDC2, name = upstreamServiceName)
        envoyDC2.verifyIsReachable(upstreamServiceDC2, upstreamServiceName)

        val serviceDC3 = consul.serverThird.operations
                .registerService(upstreamServiceDC3, name = upstreamServiceName)
        envoyDC3.verifyIsReachable(upstreamServiceDC3, upstreamServiceName)

        envoyDC3.callUpstreamServiceRepeatedly(upstreamServiceDC1, upstreamServiceDC2, upstreamServiceDC3)
            .verifyCallsCountCloseTo(upstreamServiceDC3, 90)
            .verifyNoCalls(upstreamServiceDC2, upstreamServiceDC1)

        consul.serverThird.operations.deregisterService(serviceDC3)

    }

    private fun ConsulClusterSetup.spawnInstances(service: EchoServiceExtension, numOfInstances: Int) {
        for(i in 0..numOfInstances) {
            this.operations.registerService(
                service,
                name = upstreamServiceName,
                id = "$upstreamServiceName-$i"
            )
        }
    }

}

