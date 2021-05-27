package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters

import io.envoyproxy.envoy.config.listener.v3.Filter
import io.envoyproxy.envoy.config.listener.v3.FilterChain
import io.envoyproxy.envoy.config.listener.v3.FilterChainMatch
import io.envoyproxy.envoy.extensions.filters.network.tcp_proxy.v3.TcpProxy

class TcpProxyFilterFactory {

    fun createFilter(
        clusterName: String,
        isSsl: Boolean,
        host: String = "",
        statsPrefix: String = clusterName
    ): FilterChain {
        val filterChainMatch = FilterChainMatch.newBuilder()
        if (isSsl) {
            filterChainMatch.setTransportProtocol("tls")
                .addServerNames(host)
        }
        val filter = Filter.newBuilder()
            .setName("envoy.tcp_proxy")
            .setTypedConfig(
                com.google.protobuf.Any.pack(
                    TcpProxy.newBuilder()
                        .setStatPrefix(statsPrefix)
                        .setCluster(clusterName)
                        .build()
                )
            )
        return FilterChain.newBuilder().setFilterChainMatch(filterChainMatch).addFilters(filter).build()
    }
}

