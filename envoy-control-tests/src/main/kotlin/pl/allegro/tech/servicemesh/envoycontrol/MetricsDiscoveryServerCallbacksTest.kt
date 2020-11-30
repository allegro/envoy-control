package pl.allegro.tech.servicemesh.envoycontrol

import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.Ads
import pl.allegro.tech.servicemesh.envoycontrol.config.Xds
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension
import pl.allegro.tech.servicemesh.envoycontrol.server.callbacks.MetricsDiscoveryServerCallbacks
import pl.allegro.tech.servicemesh.envoycontrol.server.callbacks.MetricsDiscoveryServerCallbacks.StreamType.ADS
import pl.allegro.tech.servicemesh.envoycontrol.server.callbacks.MetricsDiscoveryServerCallbacks.StreamType.CDS
import pl.allegro.tech.servicemesh.envoycontrol.server.callbacks.MetricsDiscoveryServerCallbacks.StreamType.EDS
import pl.allegro.tech.servicemesh.envoycontrol.server.callbacks.MetricsDiscoveryServerCallbacks.StreamType.LDS
import pl.allegro.tech.servicemesh.envoycontrol.server.callbacks.MetricsDiscoveryServerCallbacks.StreamType.RDS
import pl.allegro.tech.servicemesh.envoycontrol.server.callbacks.MetricsDiscoveryServerCallbacks.StreamType.SDS
import pl.allegro.tech.servicemesh.envoycontrol.server.callbacks.MetricsDiscoveryServerCallbacks.StreamType.UNKNOWN

class XdsMetricsDiscoveryServerCallbacksTest : MetricsDiscoveryServerCallbacksTest {
    companion object {

        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(consul)

        @JvmField
        @RegisterExtension
        val service = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val envoy = EnvoyExtension(envoyControl, service, config = Xds)
    }

    override fun consul() = consul

    override fun envoyControl() = envoyControl

    override fun service() = service

    override fun envoy() = envoy

    override fun expectedGrpcConnectionsGaugeValues() = mapOf(
        CDS to 1,
        EDS to 2, // separate streams for consul and echo
        LDS to 1,
        RDS to 2, // default_routes
        SDS to 0,
        ADS to 0,
        UNKNOWN to 0
    )
}

class AdsMetricsDiscoveryServerCallbackTest : MetricsDiscoveryServerCallbacksTest {
    companion object {

        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(consul)

        @JvmField
        @RegisterExtension
        val service = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val envoy = EnvoyExtension(envoyControl, service, config = Ads)
    }

    override fun consul() = consul

    override fun envoyControl() = envoyControl

    override fun service() = service

    override fun envoy() = envoy

    override fun expectedGrpcConnectionsGaugeValues() = mapOf(
        CDS to 0,
        EDS to 0,
        LDS to 0,
        RDS to 0,
        SDS to 0,
        ADS to 1, // all info is exchanged on one stream
        UNKNOWN to 0
    )
}

interface MetricsDiscoveryServerCallbacksTest {

    fun consul(): ConsulExtension

    fun envoyControl(): EnvoyControlExtension

    fun service(): EchoServiceExtension

    fun envoy(): EnvoyExtension

    @Test
    fun `should measure gRPC connections`() {
        // given
        val meterRegistry = envoyControl().app.meterRegistry()
        consul().server.operations.registerService(service(), name = "echo")

        // expect
        untilAsserted {
            expectedGrpcConnectionsGaugeValues().forEach { (type, value) ->
                val metric = "grpc.connections.${type.name.toLowerCase()}"
                assertThat(meterRegistry.find(metric).gauge()).isNotNull
                assertThat(meterRegistry.get(metric).gauge().value().toInt()).isEqualTo(value)
            }
        }
    }

    fun expectedGrpcConnectionsGaugeValues(): Map<MetricsDiscoveryServerCallbacks.StreamType, Int>

    @Test
    fun `should measure gRPC requests`() {
        // given
        val meterRegistry = envoyControl().app.meterRegistry()
        consul().server.operations.registerService(service(), name = "echo")

        // expect
        untilAsserted {
            listOf(CDS, EDS, LDS, RDS).forEach {
                assertThat(meterRegistry.counterValue("grpc.requests.${it.name.toLowerCase()}")).isGreaterThan(0)
            }
            listOf(SDS, ADS, UNKNOWN).forEach {
                assertThat(meterRegistry.counterValue("grpc.requests.${it.name.toLowerCase()}")).isNull()
            }
        }
    }

    fun MeterRegistry.counterValue(name: String) = this.find(name).counter()?.count()?.toInt()
}
