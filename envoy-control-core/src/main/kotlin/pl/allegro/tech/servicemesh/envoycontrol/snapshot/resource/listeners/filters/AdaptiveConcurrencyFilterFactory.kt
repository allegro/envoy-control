package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters

import com.google.protobuf.Any
import com.google.protobuf.BoolValue
import com.google.protobuf.Duration
import com.google.protobuf.UInt32Value
import io.envoyproxy.envoy.config.core.v3.RuntimeFeatureFlag
import io.envoyproxy.envoy.extensions.filters.http.adaptive_concurrency.v3.AdaptiveConcurrency
import io.envoyproxy.envoy.extensions.filters.http.adaptive_concurrency.v3.GradientControllerConfig
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter
import io.envoyproxy.envoy.type.v3.Percent
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.AdaptiveConcurrencyProperties

class AdaptiveConcurrencyFilterFactory(
    val properties: AdaptiveConcurrencyProperties
) {

    fun adaptiveConcurrencyFilter(): HttpFilter = adaptiveConcurrencyFilter

    /**
     * This filter is used to limit the number of concurrent requests to a service.
     * It is used to prevent overloading the service.
     * gradient_controller_config:
     *     sample_aggregate_percentile:
     *       value: 90
     *     concurrency_limit_params:
     *       concurrency_update_interval: 0.1s
     *     min_rtt_calc_params:
     *       jitter:
     *         value: 10
     *       interval: 60s
     *       request_count: 50
     *   enabled:
     *     default_value: true
     *     runtime_key: "adaptive_concurrency.enabled"
     */

    private val adaptiveConcurrencyFilter: HttpFilter =
        HttpFilter.newBuilder()
            .setName("envoy.filters.http.adaptive_concurrency")
            .setTypedConfig(
                Any.pack(
                    AdaptiveConcurrency.newBuilder()
                        .setGradientControllerConfig(
                            GradientControllerConfig.newBuilder()
                                .setSampleAggregatePercentile(
                                    Percent.newBuilder().setValue(90.0).build()
                                )
                                .setConcurrencyLimitParams(
                                    GradientControllerConfig.ConcurrencyLimitCalculationParams.newBuilder()
                                        .setConcurrencyUpdateInterval(
                                            Duration.newBuilder().setSeconds(1).build()
                                        )
                                        .build()
                                )
                                .setMinRttCalcParams(
                                    GradientControllerConfig.MinimumRTTCalculationParams.newBuilder()
                                        .setRequestCount(UInt32Value.of(50))
                                        .setJitter(Percent.newBuilder().setValue(10.0).build())
                                        .setInterval(Duration.newBuilder().setSeconds(60).build())
                                        .build()
                                )
                                .build()
                        )
                        .setEnabled(
                            RuntimeFeatureFlag.newBuilder()
                                .setDefaultValue(
                                    BoolValue.of(true)
                                )
                                .setRuntimeKey("adaptive_concurrency.enabled")
                                .build()
                        )
                        .build()
                )
            )
            .build()
}




