package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters

import com.google.protobuf.UInt32Value
import com.google.protobuf.util.Durations
import io.envoyproxy.envoy.extensions.common.dynamic_forward_proxy.v3.DnsCacheConfig
import io.envoyproxy.envoy.extensions.filters.http.dynamic_forward_proxy.v3.FilterConfig
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.DynamicForwardProxyProperties

class DynamicForwardProxyFilter(
    val properties: DynamicForwardProxyProperties
) {
    val filter: HttpFilter = HttpFilter.newBuilder()
        .setName("envoy.filters.http.dynamic_forward_proxy")
        .setTypedConfig(
            com.google.protobuf.Any.pack(
                FilterConfig.newBuilder()
                    .setDnsCacheConfig(
                        DnsCacheConfig.newBuilder()
                            .setName("dynamic_forward_proxy_cache_config")
                            .setDnsLookupFamily(properties.dnsLookupFamily)
                            .setHostTtl(
                                Durations.fromMillis(
                                    properties.maxHostTtl.toMillis()
                                )
                            )
                            .setMaxHosts(
                                UInt32Value.of(properties.maxCachedHosts)
                            )
                    ).build()
            )
        ).build()
}
