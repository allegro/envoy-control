package pl.allegro.tech.servicemesh.envoycontrol

import io.micrometer.core.instrument.Tags
import java.util.concurrent.TimeUnit.SECONDS
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isFrom
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isOk
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.DeltaAds
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension
import pl.allegro.tech.servicemesh.envoycontrol.utils.RECONNECTION_REQUESTS_METRIC
import pl.allegro.tech.servicemesh.envoycontrol.utils.STREAM_TYPE_TAG

class DeltaXdsReconnectionTest {

    companion object {
        private val properties = mapOf("envoy-control.envoy.snapshot.deltaXdsEnabled" to true,)

        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(consul, properties)

        @JvmField
        @RegisterExtension
        val echoService1 = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val echoService2 = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val echoClient = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val envoyEcho = EnvoyExtension(envoyControl, echoService1, DeltaAds)

        @JvmField
        @RegisterExtension
        val envoyEcho2 = EnvoyExtension(envoyControl, echoService2, DeltaAds)

        private val echoClientConfig = """
            node:
              metadata:
                proxy_settings:
                  outgoing:
                    dependencies:
                      - service: "echo"
        """.trimIndent()

        @JvmField
        @RegisterExtension
        val echoClientEnvoy = EnvoyExtension(
            envoyControl,
            localService = echoClient,
            config = DeltaAds.copy(configOverride = echoClientConfig)
        )
    }

    @Test
    fun `should increase counters on reconnect request`() {
        consul.server.operations.registerServiceWithEnvoyOnIngress(name = "echo", extension = envoyEcho)
        consul.server.operations.registerServiceWithEnvoyOnIngress(name = "echo", extension = envoyEcho2)
        consul.server.operations.registerServiceWithEnvoyOnEgress(name = "echoClient", extension = echoClientEnvoy)
        untilAsserted {
            val response = echoClientEnvoy.egressOperations.callService("echo")
            assertThat(response).isOk().isFrom(echoService1)
        }
        assertThat(getReconnectionRequestCounterValue("eds")).isNull()
        stopControlPlane()
        // to make sure that control plane has stopped
        Thread.sleep(1000)

        startControlPlane()
        untilAsserted {
            assertMetricValue("eds")
            assertMetricValue("cds")
            assertMetricValue("rds")
            assertMetricValue("lds")
        }
    }

    private fun stopControlPlane() {
        envoyControl.app.stop()
        await().atMost(30, SECONDS).untilAsserted {
            assertThatThrownBy { (envoyControl.app.isHealthy()) }.isInstanceOf(java.net.ConnectException::class.java)
        }
    }

    private fun startControlPlane() {
        envoyControl.app.run()
        untilAsserted {
            assertThat(envoyControl.app.getState()).matches { it.serviceNames().isNotEmpty() }
        }
    }

    private fun getReconnectionRequestCounterValue(type: String): Int? {
        return envoyControl.app.meterRegistry().find(RECONNECTION_REQUESTS_METRIC)
            .tags(Tags.of(STREAM_TYPE_TAG, type))
            .counter()?.count()?.toInt()
    }

    private fun assertMetricValue(urlType: String) {
        val numberOfReconnectingClients = 2
        assertThat(getReconnectionRequestCounterValue(urlType)).isNotNull().isEqualTo(numberOfReconnectingClients)
    }
}
