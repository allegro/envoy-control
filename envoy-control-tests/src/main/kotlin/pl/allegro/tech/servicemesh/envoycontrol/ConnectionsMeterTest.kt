package pl.allegro.tech.servicemesh.envoycontrol

import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.config.Ads
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration
import pl.allegro.tech.servicemesh.envoycontrol.config.Xds
import pl.allegro.tech.servicemesh.envoycontrol.server.callbacks.MeteredConnectionsCallbacks.MetricsStreamType.ADS
import pl.allegro.tech.servicemesh.envoycontrol.server.callbacks.MeteredConnectionsCallbacks.MetricsStreamType.CDS
import pl.allegro.tech.servicemesh.envoycontrol.server.callbacks.MeteredConnectionsCallbacks.MetricsStreamType.EDS
import pl.allegro.tech.servicemesh.envoycontrol.server.callbacks.MeteredConnectionsCallbacks.MetricsStreamType.LDS
import pl.allegro.tech.servicemesh.envoycontrol.server.callbacks.MeteredConnectionsCallbacks.MetricsStreamType.RDS
import pl.allegro.tech.servicemesh.envoycontrol.server.callbacks.MeteredConnectionsCallbacks.MetricsStreamType.SDS
import pl.allegro.tech.servicemesh.envoycontrol.server.callbacks.MeteredConnectionsCallbacks.MetricsStreamType.UNKNOWN

internal class XdsConnectionsMeterTest : EnvoyControlTestConfiguration() {
    companion object {

        @JvmStatic
        @BeforeAll
        fun nonAdsSetup() {
            setup(envoyConfig = Xds)
        }
    }

    @Test
    fun `should meter the gRPC connections`() {
        // given
        val meterRegistry: MeterRegistry = bean()
        registerService(name = "echo")

        untilAsserted {
            // expect
            mapOf(
                CDS to 1,
                EDS to 2, // separate streams for consul and echo
                LDS to 1,
                RDS to 2, // default_routes
                SDS to 0,
                ADS to 0,
                UNKNOWN to 0
            ).forEach { (type, value) ->
                val metric = "grpc.connections.${type.name.toLowerCase()}"
                assertThat(meterRegistry.find(metric).gauge()).isNotNull
                assertThat(meterRegistry.get(metric).gauge().value().toInt()).isEqualTo(value)
            }
        }
    }
}

internal class AdsConnectionsMeterTest : EnvoyControlTestConfiguration() {
    companion object {

        @JvmStatic
        @BeforeAll
        fun nonAdsSetup() {
            setup(envoyConfig = Ads)
        }
    }

    @Test
    fun `should meter the gRPC connections`() {
        // given
        val meterRegistry: MeterRegistry = bean()
        registerService(name = "echo")

        untilAsserted {
            // expect
            mapOf(
                CDS to 0,
                EDS to 0,
                LDS to 0,
                RDS to 0,
                SDS to 0,
                ADS to 1, // all info is exchanged on one stream
                UNKNOWN to 0
            ).forEach { (type, value) ->
                val metric = "grpc.connections.${type.name.toLowerCase()}"
                assertThat(meterRegistry.find(metric).gauge()).isNotNull
                assertThat(meterRegistry.get(metric).gauge().value().toInt()).isEqualTo(value)
            }
        }
    }
}
