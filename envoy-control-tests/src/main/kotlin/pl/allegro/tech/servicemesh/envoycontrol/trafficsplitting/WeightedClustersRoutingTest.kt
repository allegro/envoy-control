package pl.allegro.tech.servicemesh.envoycontrol.trafficsplitting

import TrafficSplitting.serviceName
import TrafficSplitting.upstreamServiceName
import callUpstreamServiceRepeatedly
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.Xds
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulMultiClusterExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlClusteredExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension
import verifyCallsCountCloseTo
import verifyIsReachable
import java.time.Duration

class WeightedClustersRoutingTest {
    companion object {
        private const val forceTrafficZone = "dc2"

        private val properties = mapOf(
            "pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.endpoints.EnvoyEndpointsFactory" to "DEBUG",
            "pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.clusters.EnvoyClustersFactory" to "DEBUG",
            "envoy-control.envoy.snapshot.stateSampleDuration" to Duration.ofSeconds(0),
            "envoy-control.sync.enabled" to true,
            "envoy-control.envoy.snapshot.load-balancing.trafficSplitting.zoneName" to forceTrafficZone,
            "envoy-control.envoy.snapshot.load-balancing.trafficSplitting.serviceByWeightsProperties.$serviceName.main" to 90,
            "envoy-control.envoy.snapshot.load-balancing.trafficSplitting.serviceByWeightsProperties.$serviceName.secondary" to 10,
            "envoy-control.envoy.snapshot.load-balancing.trafficSplitting.serviceByWeightsProperties.$serviceName.zoneByWeights.dc1" to 30,
            "envoy-control.envoy.snapshot.load-balancing.trafficSplitting.serviceByWeightsProperties.$serviceName.zoneByWeights.dc2" to 10,
            "envoy-control.envoy.snapshot.load-balancing.trafficSplitting.serviceByWeightsProperties.$serviceName.zoneByWeights.dc3" to 1,
            "envoy-control.envoy.snapshot.load-balancing.priorities.zonePriorities" to mapOf(
                "dc1" to mapOf(
                    "dc1" to 0,
                    "dc2" to 0,
                    "dc3" to 3,
                ),
                "dc2" to mapOf(
                    "dc1" to 0,
                    "dc2" to 0,
                    "dc3" to 3,
                ),
                "dc3" to mapOf(
                    "dc1" to 3,
                    "dc2" to 3,
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
        val echoServiceDC1 = EchoServiceExtension()

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
        val echoEnvoyDC1 = EnvoyExtension(envoyControl, localService = echoServiceDC1, config)
        @JvmField
        @RegisterExtension
        val echoEnvoyDC2 = EnvoyExtension(envoyControl2)

        @JvmField
        @RegisterExtension
        val echoEnvoyDC3 = EnvoyExtension(envoyControl3)
    }

    @Test
    fun `should route traffic according to weights`() {
        consul.serverFirst.operations.registerServiceWithEnvoyOnEgress(echoEnvoyDC1, name = serviceName)

        consul.serverFirst.operations.registerService(upstreamServiceDC1, name = upstreamServiceName)
        echoEnvoyDC1.verifyIsReachable(upstreamServiceDC1, upstreamServiceName)

        consul.serverSecond.operations.registerService(upstreamServiceDC2, name = upstreamServiceName)
        echoEnvoyDC1.verifyIsReachable(upstreamServiceDC2, upstreamServiceName)

        echoEnvoyDC1.callUpstreamServiceRepeatedly(upstreamServiceDC1, upstreamServiceDC2)
            .verifyCallsCountCloseTo(upstreamServiceDC1, 75)
            .verifyCallsCountCloseTo(upstreamServiceDC2, 25)
        println("snapshot: " + envoyControl.app.getGlobalSnapshot(false).toString())
    }

    @Test
    fun `should route traffic according to weights with service tag`() {
        consul.serverFirst.operations.registerServiceWithEnvoyOnEgress(echoEnvoyDC1, name = serviceName)

        consul.serverFirst.operations.registerService(upstreamServiceDC1, name = upstreamServiceName, tags = listOf("tag"))
        echoEnvoyDC1.verifyIsReachable(upstreamServiceDC1, upstreamServiceName)

        consul.serverSecond.operations.registerService(upstreamServiceDC2, name = upstreamServiceName, tags = listOf("tag"))
        echoEnvoyDC1.verifyIsReachable(upstreamServiceDC2, upstreamServiceName)

        echoEnvoyDC1.callUpstreamServiceRepeatedly(upstreamServiceDC1, upstreamServiceDC2, tag = "tag")
            .verifyCallsCountCloseTo(upstreamServiceDC1, 75)
            .verifyCallsCountCloseTo(upstreamServiceDC2, 25)
    }
}
