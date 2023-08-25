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
import verifyCallsCountGreaterThan
import verifyIsReachable
import java.time.Duration

class WeightedClustersRoutingTest {
    companion object {
        private const val forceTrafficZone = "dc2"

        private val properties = mapOf(
            "envoy-control.envoy.snapshot.stateSampleDuration" to Duration.ofSeconds(0),
            "envoy-control.sync.enabled" to true,
            "envoy-control.envoy.snapshot.loadBalancing.trafficSplitting.zoneName" to forceTrafficZone,
            "envoy-control.envoy.snapshot.loadBalancing.trafficSplitting.serviceByWeightsProperties"
                to mutableMapOf(serviceName to mutableMapOf("main" to 90, "secondary" to 10)),
            "envoy-control.envoy.snapshot.loadBalancing.priorities.zonePriorities" to mapOf(
                "dc1" to mapOf(
                    "dc1" to 0,
                    "dc2" to 1
                ),
                "dc2" to mapOf(
                    "dc1" to 1,
                    "dc2" to 0,
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
        val echoServiceDC1 = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val upstreamServiceDC1 = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val upstreamServiceDC2 = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val echoEnvoyDC1 = EnvoyExtension(envoyControl, localService = echoServiceDC1, config)

    }

    @Test
    fun `should route traffic according to weights`() {
        consul.serverFirst.operations.registerServiceWithEnvoyOnEgress(echoEnvoyDC1, name = serviceName)

        consul.serverFirst.operations.registerService(upstreamServiceDC1, name = upstreamServiceName)
        echoEnvoyDC1.verifyIsReachable(upstreamServiceDC1, upstreamServiceName)

        consul.serverSecond.operations.registerService(upstreamServiceDC2, name = upstreamServiceName)
        echoEnvoyDC1.verifyIsReachable(upstreamServiceDC2, upstreamServiceName)

        echoEnvoyDC1.callUpstreamServiceRepeatedly(upstreamServiceDC1, upstreamServiceDC2)
            .verifyCallsCountCloseTo(upstreamServiceDC1, 90)
            .verifyCallsCountGreaterThan(upstreamServiceDC2, 1)
    }
}
