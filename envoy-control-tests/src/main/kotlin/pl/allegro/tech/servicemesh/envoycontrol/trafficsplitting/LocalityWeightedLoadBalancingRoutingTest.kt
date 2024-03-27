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
import verifyCallsCountEq
import verifyIsReachable
import java.time.Duration

class LocalityWeightedLoadBalancingRoutingTest {
    companion object {
        private const val forceTrafficZone = "dc2"

        private val properties = mapOf(
            "pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.endpoints.EnvoyEndpointsFactory" to "DEBUG",
            "pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.clusters.EnvoyClustersFactory" to "DEBUG",
            "envoy-control.envoy.snapshot.stateSampleDuration" to Duration.ofSeconds(0),
            "envoy-control.sync.enabled" to true,
            "envoy-control.envoy.snapshot.load-balancing.trafficSplitting.zoneName" to forceTrafficZone,
            "envoy-control.envoy.snapshot.load-balancing.trafficSplitting.zonesAllowingTrafficSplitting" to "dc1",
            "envoy-control.envoy.snapshot.load-balancing.trafficSplitting.weightsByService.$serviceName.weightByZone.dc1" to 30,
            "envoy-control.envoy.snapshot.load-balancing.trafficSplitting.weightsByService.$serviceName.weightByZone.dc2" to 10,
            "envoy-control.envoy.snapshot.load-balancing.trafficSplitting.weightsByService.$serviceName.weightByZone.dc3" to 1,
            "envoy-control.envoy.snapshot.load-balancing.priorities.zonePriorities" to mapOf(
                "dc1" to mapOf(
                    "dc1" to 0,
                    "dc2" to 1,
                    "dc3" to 3,
                ),
                "dc2" to mapOf(
                    "dc1" to 1,
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
                      - service: "service-2"
        """.trimIndent()

        private val config = Xds.copy(configOverride = echo2Config, serviceName = "echo2")
        private val configEcho = Xds.copy(configOverride = echo2Config, serviceName = "echo")

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
        val echo2ServiceDC1 = EchoServiceExtension()

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
        val echo2EnvoyDC1 = EnvoyExtension(envoyControl, localService = echo2ServiceDC1, config)

        @JvmField
        @RegisterExtension
        val echoEnvoyDC1 = EnvoyExtension(envoyControl, localService = echoServiceDC1, configEcho)

        @JvmField
        @RegisterExtension
        val echoEnvoyDC2 = EnvoyExtension(envoyControl2)
    }

    @Test
    fun `should route traffic according to weights`() {
        consul.serverFirst.operations.registerServiceWithEnvoyOnEgress(echo2EnvoyDC1, name = serviceName)

        consul.serverFirst.operations.registerService(upstreamServiceDC1, name = upstreamServiceName)
        echo2EnvoyDC1.verifyIsReachable(upstreamServiceDC1, upstreamServiceName)

        consul.serverSecond.operations.registerService(upstreamServiceDC2, name = upstreamServiceName)
        echo2EnvoyDC1.verifyIsReachable(upstreamServiceDC2, upstreamServiceName)

        echo2EnvoyDC1.callUpstreamServiceRepeatedly(upstreamServiceDC1, upstreamServiceDC2)
            .verifyCallsCountCloseTo(upstreamServiceDC1, 75)
            .verifyCallsCountCloseTo(upstreamServiceDC2, 25)
            .verifyCallsCountEq(upstreamServiceDC3, 0)
    }

    @Test
    fun `should route traffic according to weights with service tag`() {
        consul.serverFirst.operations.registerServiceWithEnvoyOnEgress(echo2EnvoyDC1, name = serviceName)

        consul.serverFirst.operations.registerService(upstreamServiceDC1, name = upstreamServiceName, tags = listOf("tag"))
        echo2EnvoyDC1.verifyIsReachable(upstreamServiceDC1, upstreamServiceName)

        consul.serverSecond.operations.registerService(upstreamServiceDC2, name = upstreamServiceName, tags = listOf("tag"))
        echo2EnvoyDC1.verifyIsReachable(upstreamServiceDC2, upstreamServiceName)

        echo2EnvoyDC1.callUpstreamServiceRepeatedly(upstreamServiceDC1, upstreamServiceDC2, tag = "tag")
            .verifyCallsCountCloseTo(upstreamServiceDC1, 75)
            .verifyCallsCountCloseTo(upstreamServiceDC2, 25)
            .verifyCallsCountEq(upstreamServiceDC3, 0)
    }

    @Test
    fun `should not split traffic for not listed service`() {
        consul.serverFirst.operations.registerServiceWithEnvoyOnEgress(echoEnvoyDC1, name = "echo")

        consul.serverFirst.operations.registerService(upstreamServiceDC1, name = upstreamServiceName,)
        echoEnvoyDC1.verifyIsReachable(upstreamServiceDC1, upstreamServiceName)

        consul.serverSecond.operations.registerService(upstreamServiceDC2, name = upstreamServiceName)
        echoEnvoyDC2.verifyIsReachable(upstreamServiceDC2, upstreamServiceName)

        echoEnvoyDC1.callUpstreamServiceRepeatedly(upstreamServiceDC1, upstreamServiceDC2)
            .verifyCallsCountEq(upstreamServiceDC1, 100)
            .verifyCallsCountEq(upstreamServiceDC2, 0)
            .verifyCallsCountEq(upstreamServiceDC3, 0)
    }
}
