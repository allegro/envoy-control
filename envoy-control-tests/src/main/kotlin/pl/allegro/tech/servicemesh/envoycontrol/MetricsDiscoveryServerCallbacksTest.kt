package pl.allegro.tech.servicemesh.envoycontrol

import io.micrometer.core.instrument.Tags
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.Ads
import pl.allegro.tech.servicemesh.envoycontrol.config.DeltaAds
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
import pl.allegro.tech.servicemesh.envoycontrol.utils.CONNECTION_TYPE_TAG
import pl.allegro.tech.servicemesh.envoycontrol.utils.CONNECTIONS_METRIC
import pl.allegro.tech.servicemesh.envoycontrol.utils.DISCOVERY_REQ_TYPE_TAG
import pl.allegro.tech.servicemesh.envoycontrol.utils.REQUESTS_METRIC
import pl.allegro.tech.servicemesh.envoycontrol.utils.STREAM_TYPE_TAG
import java.util.function.Consumer
import java.util.function.Predicate

@Disabled
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

    override fun expectedGrpcRequestsCounterValues() = mapOf(
        CDS.name.lowercase() to isGreaterThanZero(),
        EDS.name.lowercase() to isGreaterThanZero(),
        LDS.name.lowercase() to isGreaterThanZero(),
        RDS.name.lowercase() to isGreaterThanZero(),
        SDS.name.lowercase() to isNull(),
        ADS.name.lowercase() to isNull(),
        UNKNOWN.name.lowercase() to isNull(),
    )

    override fun expectedGrpcRequestsDeltaCounterValues() = mapOf(
        CDS.name.lowercase() to isNull(),
        EDS.name.lowercase() to isNull(),
        LDS.name.lowercase() to isNull(),
        RDS.name.lowercase() to isNull(),
        SDS.name.lowercase() to isNull(),
        ADS.name.lowercase() to isNull(),
        UNKNOWN.name.lowercase() to isNull(),
    )
}

@Disabled
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
    )

    override fun expectedGrpcRequestsDeltaCounterValues() = mapOf(
        CDS.name.lowercase() to isNull(),
        EDS.name.lowercase() to isNull(),
        LDS.name.lowercase() to isNull(),
        RDS.name.lowercase() to isNull(),
        SDS.name.lowercase() to isNull(),
        ADS.name.lowercase() to isNull(),
        UNKNOWN.name.lowercase() to isNull(),
    )
}

@Disabled
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
        CDS to 0,
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
    )

    override fun expectedGrpcRequestsDeltaCounterValues() = mapOf(
        CDS.name.lowercase() to isGreaterThanZero(),
        EDS.name.lowercase() to isGreaterThanZero(),
        LDS.name.lowercase() to isGreaterThanZero(),
        RDS.name.lowercase() to isGreaterThanZero(),
        SDS.name.lowercase() to isNull(),
        ADS.name.lowercase() to isNull(),
        UNKNOWN.name.lowercase() to isNull(),
    )
}

@Disabled
interface MetricsDiscoveryServerCallbacksTest {
    companion object {
        private val logger by logger()
    }

    fun consul(): ConsulExtension

    fun envoyControl(): EnvoyControlExtension

    fun service(): EchoServiceExtension

    fun envoy(): EnvoyExtension

    fun expectedGrpcConnectionsGaugeValues(): Map<MetricsDiscoveryServerCallbacks.StreamType, Int>

    fun expectedGrpcRequestsCounterValues(): Map<String, (Int?) -> Boolean>

    fun expectedGrpcRequestsDeltaCounterValues(): Map<String, (Int?) -> Boolean>

    fun isGreaterThanZero() = { x: Int? -> x!! > 0 }

    fun isNull() = { x: Int? -> x == null }

    @Test
    fun `should measure gRPC connections`() {
        // given
        val meterRegistry = envoyControl().app.meterRegistry()
        consul().server.operations.registerService(service(), name = "echo")
        for (meter in meterRegistry.meters) {
            print(meter.toString())
        }
        // expect
        untilAsserted {
            expectedGrpcConnectionsGaugeValues().forEach { (type, value) ->
                val metric = CONNECTIONS_METRIC
                assertThat(
                    meterRegistry.find(metric)
                        .tags(Tags.of(STREAM_TYPE_TAG, type.name.lowercase(), CONNECTION_TYPE_TAG, "grpc")).gauge()
                ).isNotNull
                assertThat(
                    meterRegistry.get(metric)
                        .tags(Tags.of(STREAM_TYPE_TAG, type.name.lowercase(), CONNECTION_TYPE_TAG, "grpc")).gauge().value()
                        .toInt()
                ).isEqualTo(value)
            }
        }
    }

    @Test
    fun `should measure gRPC requests`() {
        // given
        consul().server.operations.registerService(service(), name = "echo")

        // expect
        untilAsserted {
            expectedGrpcRequestsCounterValues().forEach {
                assertCondition(it.key, it.value, "total")
            }
        }
    }

    private fun assertCondition(type: String, condition: Predicate<Int?>, reqTpe: String) {
        val counterValue =
            envoyControl().app.meterRegistry().find(REQUESTS_METRIC)
                .tags(Tags.of(STREAM_TYPE_TAG, type, DISCOVERY_REQ_TYPE_TAG, reqTpe, CONNECTION_TYPE_TAG, "grpc"))
                .counter()?.count()?.toInt()
        logger.info("$type $counterValue")
        assertThat(counterValue).satisfies(Consumer { condition.test(it) })
    }
}
