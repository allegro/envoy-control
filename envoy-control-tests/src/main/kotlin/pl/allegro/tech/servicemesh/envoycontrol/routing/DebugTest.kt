package pl.allegro.tech.servicemesh.envoycontrol.routing

import io.micrometer.core.instrument.MeterRegistry
import okhttp3.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.chaos.api.NetworkDelay
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtensionBase
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlTestApp
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.Health
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.SnapshotDebugResponse
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension
import pl.allegro.tech.servicemesh.envoycontrol.services.ServicesState

class DebugTest {

    companion object {
        // language=yaml
        private val config = """
            {}
        """.trimIndent()

        @JvmField
        @RegisterExtension
        val echoService = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val envoy = EnvoyExtension(
            envoyControl = FakeEnvoyControl(),
            config = EnvoyConfig("envoy/debug/config_static.yaml", configOverride = config),
            localService = echoService
        )
    }

    @Test
    @ExtendWith
    fun debug() {

        // envoy.waitForAvailableEndpoints()  // TODO: check it
        // envoy.waitForReadyServices() // TODO: check it
        // envoy.waitForClusterEndpointHealthy() // TODO: check it

        val adminUrl = envoy.container.adminUrl()
        val egressUrl = envoy.container.egressListenerUrl()


        assertThat(1).isEqualTo(2)

    }
}


private class FakeEnvoyControl : EnvoyControlExtensionBase {
    override val app: EnvoyControlTestApp = object : EnvoyControlTestApp {
        override val appPort: Int
            get() { throw UnsupportedOperationException() }
        override val grpcPort: Int = 0
        override val appName: String
            get() { throw UnsupportedOperationException() }

        override fun run() {
            throw UnsupportedOperationException()
        }

        override fun stop() {
            throw UnsupportedOperationException()
        }

        override fun isHealthy(): Boolean {
            throw UnsupportedOperationException()
        }

        override fun getState(): ServicesState {
            throw UnsupportedOperationException()
        }

        override fun getSnapshot(nodeJson: String): SnapshotDebugResponse {
            throw UnsupportedOperationException()
        }

        override fun getGlobalSnapshot(xds: Boolean?): SnapshotDebugResponse {
            throw UnsupportedOperationException()
        }

        override fun getHealthStatus(): Health {
            throw UnsupportedOperationException()
        }

        override fun postChaosFaultRequest(
            username: String,
            password: String,
            networkDelay: NetworkDelay
        ): Response {
            throw UnsupportedOperationException()
        }

        override fun getExperimentsListRequest(username: String, password: String): Response {
            throw UnsupportedOperationException()
        }

        override fun deleteChaosFaultRequest(
            username: String,
            password: String,
            faultId: String
        ): Response {
            throw UnsupportedOperationException()
        }

        override fun meterRegistry(): MeterRegistry {
            throw UnsupportedOperationException()
        }
    }

    override fun beforeAll(context: ExtensionContext?) {}

    override fun afterAll(context: ExtensionContext?) {}
}
