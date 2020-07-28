package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isFrom
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isOk
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension

class OutlierDetectionTest {

    companion object {

        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(consul, mapOf(
            "envoy-control.envoy.snapshot.cluster-outlier-detection.enabled" to true
        ))

        @JvmField
        @RegisterExtension
        val healthyService = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val unhealthyService = EchoServiceExtension(shared = false)

        @JvmField
        @RegisterExtension
        val envoy = EnvoyExtension(envoyControl)
    }

    @Test
    fun `should not send requests to instance when outlier check failed`() {
        // given
        val unhealthyIp = unhealthyService.container.ipAddress()
        consul.server.operations.registerService(healthyService, name = "echo")
        consul.server.operations.registerService(unhealthyService, name = "echo")
        unhealthyService.container.stop()

        untilAsserted {
            // when
            envoy.egressOperations.callService("echo")

            // then
            assertThat(hasOutlierCheckFailed(cluster = "echo", unhealthyIp = unhealthyIp)).isTrue()
        }

        // when
        repeat(times = 10) {
            // when
            val response = envoy.egressOperations.callService("echo")

            // then
            assertThat(response).isOk().isFrom(healthyService)
        }
    }

    fun hasOutlierCheckFailed(cluster: String, unhealthyIp: String): Boolean {
        return envoy.container.admin()
            .hostStatus(cluster, unhealthyIp)
            ?.healthStatus
            ?.failedOutlierCheck
            ?: false
    }
}
