package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.Ads
import pl.allegro.tech.servicemesh.envoycontrol.config.DeltaAds
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
import java.time.Duration

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

    override fun expectedGrpcRequestsCounterValues() = mapOf(
        CDS.name.lowercase() to isGreaterThanZero(),
        EDS.name.lowercase() to isGreaterThanZero(),
        LDS.name.lowercase() to isGreaterThanZero(),
        RDS.name.lowercase() to isGreaterThanZero(),
        SDS.name.lowercase() to isNull(),
        ADS.name.lowercase() to isNull(),
        UNKNOWN.name.lowercase() to isNull(),
        "${CDS.name.lowercase()}.delta" to isNull(),
        "${EDS.name.lowercase()}.delta" to isNull(),
        "${LDS.name.lowercase()}.delta" to isNull(),
        "${RDS.name.lowercase()}.delta" to isNull(),
        "${SDS.name.lowercase()}.delta" to isNull(),
        "${ADS.name.lowercase()}.delta" to isNull(),
        "${UNKNOWN.name.lowercase()}.delta" to isNull()
    )
}

class DeltaAdsMetricsDiscoveryServerCallbackTest : MetricsDiscoveryServerCallbacksTest {
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
        val envoy = EnvoyExtension(envoyControl, service, config = DeltaAds)
    }

    override fun consul() = consul

    override fun envoyControl() = envoyControl

    override fun service() = service

    override fun envoy() = envoy

    override fun expectedGrpcConnectionsGaugeValues() = mapOf(
        CDS to 1,
        EDS to 0,
        LDS to 0,
        RDS to 0,
        SDS to 0,
        ADS to 1, // all info is exchanged on one stream
        UNKNOWN to 0
    )

    override fun expectedGrpcRequestsCounterValues() = mapOf(
        CDS.name.lowercase() to isNull(),
        EDS.name.lowercase() to isNull(),
        LDS.name.lowercase() to isNull(),
        RDS.name.lowercase() to isNull(),
        SDS.name.lowercase() to isNull(),
        ADS.name.lowercase() to isNull(),
        UNKNOWN.name.lowercase() to isNull(),
        "${CDS.name.lowercase()}.delta" to isGreaterThanZero(),
        "${EDS.name.lowercase()}.delta" to isGreaterThanZero(),
        "${LDS.name.lowercase()}.delta" to isGreaterThanZero(),
        "${RDS.name.lowercase()}.delta" to isGreaterThanZero(),
        "${SDS.name.lowercase()}.delta" to isNull(),
        "${ADS.name.lowercase()}.delta" to isNull(),
        "${UNKNOWN.name.lowercase()}.delta" to isNull()
    )
}

interface MetricsDiscoveryServerCallbacksTest {

    fun consul(): ConsulExtension

    fun envoyControl(): EnvoyControlExtension

    fun service(): EchoServiceExtension

    fun envoy(): EnvoyExtension

    fun expectedGrpcConnectionsGaugeValues(): Map<MetricsDiscoveryServerCallbacks.StreamType, Int>

    fun expectedGrpcRequestsCounterValues(): Map<String, (Int?) -> Boolean>

    fun isGreaterThanZero() = { x: Int? -> x != null && x > 0 }

    fun isNull() = { x: Int? -> x == null }

    @Test
    fun `should measure gRPC connections`() {
        // given
        val meterRegistry = envoyControl().app.meterRegistry()
        consul().server.operations.registerService(service(), name = "echo")

        // expect
        untilAsserted(wait = Duration.ofSeconds(5)) {
            expectedGrpcConnectionsGaugeValues().forEach { (type, value) ->
                val metric = "grpc.connections.${type.name.lowercase()}"
                assertThat(meterRegistry.find(metric).gauge())
                    .withFailMessage("Metric $metric should not be null")
                    .isNotNull
                    .withFailMessage("Value of metric $metric should be $value")
                    .matches { it.value().toInt() == value }
            }
        }
    }

    @Test
    fun `should measure gRPC requests`() {
        // given
        val meterRegistry = envoyControl().app.meterRegistry()
        consul().server.operations.registerService(service(), name = "echo")

        // expect
        untilAsserted(wait = Duration.ofSeconds(5)) {
            expectedGrpcRequestsCounterValues().forEach { (type, condition) ->
                val metric = "grpc.requests.$type"
                assertThat(meterRegistry.find(metric).counter()?.count()?.toInt())
                    .withFailMessage("Metric $metric does not meet the condition")
                    .matches(condition)
            }
        }
    }
}
