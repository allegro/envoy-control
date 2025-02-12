package pl.allegro.tech.servicemesh.envoycontrol.routing

import io.micrometer.core.instrument.MeterRegistry
import okhttp3.Response
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
import pl.allegro.tech.servicemesh.envoycontrol.services.ServicesState

class DebugTest {

    companion object {
        // language=yaml
        private val config = """
          static_resources:
            listeners:
            - name: egress
              address: { socket_address: { address: 0.0.0.0, port_value: 5000 }}
              filter_chains:
              - filters:
                - name: envoy.filters.network.http_connection_manager
                  typed_config:
                    "@type": type.googleapis.com/envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager
                    stat_prefix: e
                    route_config: {}
            - name: ingress
              address: { socket_address: { address: 0.0.0.0, port_value: 5001 }}
              filter_chains:
              - filters:
                - name: envoy.filters.network.http_connection_manager
                  typed_config:
                    "@type": type.googleapis.com/envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager
                    stat_prefix: i
                    route_config: {}
          
          admin:
            address: { socket_address: { address: 0.0.0.0, port_value: 10000 }}
        """.trimIndent()

        @JvmField
        @RegisterExtension
        val envoy = EnvoyExtension(
            envoyControl = FakeEnvoyControl(),
            config = EnvoyConfig("envoy/empty.yaml", configOverride = config),
        )
    }

    @Test
    @ExtendWith
    fun debug() {


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
