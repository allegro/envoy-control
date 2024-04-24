package pl.allegro.tech.servicemesh.envoycontrol.trafficsplitting

import TrafficSplitting.DEFAULT_PRIORITIES
import TrafficSplitting.FORCE_TRAFFIC_ZONE
import TrafficSplitting.SERVICE_NAME
import TrafficSplitting.UPSTREAM_SERVICE_NAME
import callUpstreamServiceRepeatedly
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.Xds
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulMultiClusterExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlClusteredExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension
import pl.allegro.tech.servicemesh.envoycontrol.trafficsplitting.LocalityWeightedLoadBalancingTest.Companion.upstreamServiceDC3
import verifyCallsCountEq
import verifyIsReachable
import java.time.Duration

class LocalityWeightedLoadBalancingUnlistedServiceTest {
    companion object {
        private val properties = mapOf(
            "envoy-control.envoy.snapshot.stateSampleDuration" to Duration.ZERO,
            "envoy-control.sync.enabled" to true,
            "envoy-control.envoy.snapshot.loadBalancing.trafficSplitting.zoneName" to FORCE_TRAFFIC_ZONE,
            "envoy-control.envoy.snapshot.loadBalancing.trafficSplitting.zonesAllowingTrafficSplitting" to listOf("dc1"),
            "envoy-control.envoy.snapshot.loadBalancing.priorities.zonePriorities" to DEFAULT_PRIORITIES
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

        @JvmField
        @RegisterExtension
        val echoEnvoyDC2 = EnvoyExtension(envoyControl2)
    }

    @Test
    fun `should not split traffic for not listed service`() {
        consul.serverFirst.operations.registerServiceWithEnvoyOnEgress(echoEnvoyDC1, name = SERVICE_NAME)

        consul.serverFirst.operations.registerService(upstreamServiceDC1, name = UPSTREAM_SERVICE_NAME)
        echoEnvoyDC1.verifyIsReachable(upstreamServiceDC1, UPSTREAM_SERVICE_NAME)

        consul.serverSecond.operations.registerService(upstreamServiceDC2, name = UPSTREAM_SERVICE_NAME)
        echoEnvoyDC2.verifyIsReachable(upstreamServiceDC2, UPSTREAM_SERVICE_NAME)

        echoEnvoyDC1.callUpstreamServiceRepeatedly(upstreamServiceDC1, upstreamServiceDC2)
            .verifyCallsCountEq(upstreamServiceDC1, 100)
            .verifyCallsCountEq(upstreamServiceDC2, 0)
            .verifyCallsCountEq(upstreamServiceDC3, 0)
    }
}
