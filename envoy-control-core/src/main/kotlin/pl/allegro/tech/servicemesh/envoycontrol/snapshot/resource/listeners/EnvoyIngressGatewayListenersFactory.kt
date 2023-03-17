package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners

import io.envoyproxy.envoy.config.core.v3.Address
import io.envoyproxy.envoy.config.core.v3.SocketAddress
import io.envoyproxy.envoy.config.listener.v3.Filter
import io.envoyproxy.envoy.config.listener.v3.FilterChain
import io.envoyproxy.envoy.config.listener.v3.Listener
import io.envoyproxy.envoy.extensions.filters.network.tcp_proxy.v3.TcpProxy
import pl.allegro.tech.servicemesh.envoycontrol.groups.AllServicesIngressGatewayGroup
import pl.allegro.tech.servicemesh.envoycontrol.groups.IngressGatewayGroup
import pl.allegro.tech.servicemesh.envoycontrol.groups.ListenersConfig
import pl.allegro.tech.servicemesh.envoycontrol.groups.ServicesIngressGatewayGroup
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.GlobalSnapshot
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.IngressGatewayPortMappingsCache
import com.google.protobuf.Any as ProtobufAny

@Suppress("MagicNumber")
class EnvoyIngressGatewayListenersFactory(
    private val mappingsCache: IngressGatewayPortMappingsCache
) {

    fun createListeners(group: IngressGatewayGroup, globalSnapshot: GlobalSnapshot): List<Listener> {
        if (group.listenersConfig == null) {
            return listOf()
        }
        val listenersConfig: ListenersConfig = group.listenersConfig!!

        return createIngressListener(group, listenersConfig, globalSnapshot)
    }

    private fun createIngressListener(
        group: IngressGatewayGroup,
        listenersConfig: ListenersConfig,
        globalSnapshot: GlobalSnapshot
    ): List<Listener> {

        val clusters = when (group) {
            is AllServicesIngressGatewayGroup -> globalSnapshot.allServicesNames.sorted()
            is ServicesIngressGatewayGroup -> group.proxySettings.outgoing.getServiceDependencies().map { it.service }
                .sorted()
        }
        val mappings = mutableMapOf<String, Int>()
        val listeners = clusters.mapIndexed { index, cluster ->
            val port = listenersConfig.ingressPort + index
            val listener = Listener.newBuilder()
                .setName("ingress_listener_for_$cluster")
                .setAddress(
                    Address.newBuilder().setSocketAddress(
                        SocketAddress.newBuilder()
                            .setPortValue(port)
                            .setAddress(listenersConfig.ingressHost)
                    )
                )
            listener.addFilterChains(
                FilterChain.newBuilder()
                    .addFilters(
                        Filter.newBuilder().setName("envoy.filters.network.tcp_proxy")
                            .setTypedConfig(
                                ProtobufAny.pack(
                                    TcpProxy.newBuilder().setCluster(cluster)
                                        .setStatPrefix("${cluster}_tcp")
                                        .build()
                                )
                            )
                    )
            )
            mappings[cluster] = port
            listener.build()
        }
        mappingsCache.addMapping(group, mappings)
        return listeners
    }
}
