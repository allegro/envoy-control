package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters

import com.google.protobuf.Any
import com.google.protobuf.util.Durations
import io.envoyproxy.envoy.config.filter.fault.v2.FaultDelay
import io.envoyproxy.envoy.config.filter.http.fault.v2.HTTPFault
import io.envoyproxy.envoy.config.filter.network.http_connection_manager.v2.HttpFilter
import io.envoyproxy.envoy.type.FractionalPercent

class FaultFilterFactory {
    companion object {
        fun fixedDelayFilter(
            upstreamCluster: String, fixedDelay: Long, percentageValue: Int = 100
        ): HttpFilter {
            val percentage = FractionalPercent.newBuilder()
                .setNumerator(percentageValue)
                .setDenominator(FractionalPercent.DenominatorType.HUNDRED)
                .build()

            val faultDelay = FaultDelay.newBuilder()
                .setPercentage(percentage)
                .setFixedDelay(Durations.fromMillis(fixedDelay))
                .build()

            return HttpFilter.newBuilder()
                .setName("envoy.filters.http.fault")
                .setTypedConfig(Any.pack(
                    HTTPFault.newBuilder()
                        .setUpstreamCluster(upstreamCluster)
                        .setDelay(faultDelay)
                        .build()
                )).build()
        }
    }
}
