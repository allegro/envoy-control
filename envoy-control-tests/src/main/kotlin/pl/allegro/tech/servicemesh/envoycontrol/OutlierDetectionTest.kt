package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlRunnerTestApp
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyAdmin

internal class OutlierDetectionTest : EnvoyControlTestConfiguration() {
    companion object {

        private val properties = mapOf(
            "envoy-control.envoy.snapshot.cluster-outlier-detection.enabled" to true
        )

        @JvmStatic
        @BeforeAll
        fun setupOutlierDetectionTest() {
            setup(appFactoryForEc1 = { consulPort -> EnvoyControlRunnerTestApp(properties, consulPort) })
        }
    }

    @AfterEach
    fun after() {
        cleanupTest()
        if (!echoContainer2.isRunning) {
            echoContainer2.start()
        }
    }

    @Test
    fun `should not send requests to instance when outlier check failed`() {
        // given
        val unhealthyIp = echoContainer2.ipAddress()
        registerService(name = "echo")
        registerService(name = "echo", container = echoContainer2)
        echoContainer2.stop()

        untilAsserted {
            // when
            callEcho()

            // then
            assertThat(hasOutlierCheckFailed(cluster = "echo", unhealthyIp = unhealthyIp)).isTrue()
        }

        // when
        repeat(times = 10) {
            // when
            val response = callEcho()

            // then
            assertThat(response).isOk().isFrom(echoContainer)
        }
    }

    private fun hasOutlierCheckFailed(cluster: String, unhealthyIp: String): Boolean {
        return EnvoyAdmin(address = envoyContainer1.adminUrl())
            .hostStatus(cluster, unhealthyIp)
            ?.healthStatus
            ?.failedOutlierCheck
            ?: false
    }
}
