package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners

import com.google.protobuf.BoolValue
import com.google.protobuf.Duration
import com.google.protobuf.UInt32Value
import io.envoyproxy.envoy.config.core.v3.Address
import io.envoyproxy.envoy.config.core.v3.SocketAddress
import io.envoyproxy.envoy.config.core.v3.TrafficDirection
import io.envoyproxy.envoy.config.core.v3.TransportSocket
import io.envoyproxy.envoy.config.listener.v3.Filter
import io.envoyproxy.envoy.config.listener.v3.FilterChain
import io.envoyproxy.envoy.config.listener.v3.FilterChainMatch
import io.envoyproxy.envoy.config.listener.v3.Listener
import io.envoyproxy.envoy.config.listener.v3.ListenerFilter
import io.envoyproxy.envoy.extensions.common.tap.v3.AdminConfig
import io.envoyproxy.envoy.extensions.common.tap.v3.CommonExtensionConfig
import io.envoyproxy.envoy.extensions.filters.listener.http_inspector.v3.HttpInspector
import io.envoyproxy.envoy.extensions.filters.listener.tls_inspector.v3.TlsInspector
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter
import io.envoyproxy.envoy.extensions.transport_sockets.tap.v3.Tap
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CommonTlsContext
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.DownstreamTlsContext
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.SdsSecretConfig
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.TlsParameters
import pl.allegro.tech.servicemesh.envoycontrol.groups.DomainDependency
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group
import pl.allegro.tech.servicemesh.envoycontrol.groups.ListenersConfig
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.EnvoySnapshotFactory.Companion.DEFAULT_HTTP_PORT
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.GlobalSnapshot
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters.EnvoyHttpFilters
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters.HttpConnectionManagerFactory
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters.TcpProxyFilterFactory
import com.google.protobuf.Any as ProtobufAny

typealias HttpFilterFactory = (node: Group, snapshot: GlobalSnapshot) -> HttpFilter?

@Suppress("MagicNumber")
class EnvoyListenersFactory(
    private val snapshotProperties: SnapshotProperties,
    envoyHttpFilters: EnvoyHttpFilters
) {

    companion object {
        const val DOMAIN_PROXY_LISTENER_ADDRESS = "0.0.0.0"
    }

    private val ingressFilters: List<HttpFilterFactory> = envoyHttpFilters.ingressFilters
    private val egressFilters: List<HttpFilterFactory> = envoyHttpFilters.egressFilters
    private val egressRdsInitialFetchTimeout: Duration = Duration.newBuilder().setSeconds(20).build()
    private val ingressRdsInitialFetchTimeout: Duration = Duration.newBuilder().setSeconds(30).build()
    private val tcpProxyFilterFactory: TcpProxyFilterFactory = TcpProxyFilterFactory()
    private val httpConnectionManagerFactory = HttpConnectionManagerFactory(snapshotProperties)
    private val tlsProperties = snapshotProperties.incomingPermissions.tlsAuthentication
    private val requireClientCertificate = BoolValue.of(tlsProperties.requireClientCertificate)

    private val downstreamTlsContext = DownstreamTlsContext.newBuilder()
        .setRequireClientCertificate(requireClientCertificate)
        .setCommonTlsContext(
            CommonTlsContext.newBuilder()
                .setTlsParams(
                    TlsParameters.newBuilder()
                        .setTlsMinimumProtocolVersion(tlsProperties.protocol.minimumVersion)
                        .setTlsMaximumProtocolVersion(tlsProperties.protocol.maximumVersion)
                        .addAllCipherSuites(tlsProperties.protocol.cipherSuites)
                        .build()
                )
                .addTlsCertificateSdsSecretConfigs(
                    SdsSecretConfig.newBuilder()
                        .setName(tlsProperties.tlsCertificateSecretName)
                        .build()
                )
                .setValidationContextSdsSecretConfig(
                    SdsSecretConfig.newBuilder()
                        .setName(tlsProperties.validationContextSecretName)
                        .build()
                )
        )

    private val downstreamTlsTransportSocket = TransportSocket.newBuilder()
        .setName("envoy.transport_sockets.tls")
        .setTypedConfig(ProtobufAny.pack(downstreamTlsContext.build()))
        .build()

    private val downstreamTransportSocketWithTap = TransportSocket.newBuilder()
        .setName("envoy.transport_sockets.tap")
        .setTypedConfig(
            ProtobufAny.pack(
                Tap.newBuilder().setCommonConfig(
                    CommonExtensionConfig.newBuilder().setAdminConfig(
                        AdminConfig.newBuilder().setConfigId("downstream_tap")
                    )
                ).setTransportSocket(downstreamTlsTransportSocket).build()
            )
        ).build()

    private val tlsInspectorFilter = ListenerFilter
        .newBuilder()
        .setName("envoy.filters.listener.tls_inspector")
        .setTypedConfig(
            ProtobufAny.pack(
                TlsInspector.getDefaultInstance()
            )
        )
        .build()
    private val httpInspectorFilter = ListenerFilter.newBuilder()
        .setName("envoy.filters.listener.http_inspector")
        .setTypedConfig(
            ProtobufAny.pack(
                HttpInspector.getDefaultInstance()
            )
        ).build()

    private enum class TransportProtocol(
        value: String,
        val filterChainMatch: FilterChainMatch = FilterChainMatch.newBuilder().setTransportProtocol(value).build()
    ) {
        RAW_BUFFER("raw_buffer"),
        TLS("tls")
    }

    fun createListeners(group: Group, globalSnapshot: GlobalSnapshot): List<Listener> {
        if (group.listenersConfig == null) {
            return listOf()
        }
        val listenersConfig: ListenersConfig = group.listenersConfig!!
        val listeners = listOf(
            createIngressListener(group, listenersConfig, globalSnapshot),
            createEgressListener(group, listenersConfig, globalSnapshot)
        )
        return if (group.listenersConfig?.useTransparentProxy == true) {
            listeners + createEgressVirtualListeners(group, globalSnapshot)
        } else {
            listeners
        }
    }

    private fun createEgressListener(
        group: Group,
        listenersConfig: ListenersConfig,
        globalSnapshot: GlobalSnapshot
    ): Listener {
        val listener = Listener.newBuilder()
            .setName("egress_listener")
            .setAddress(
                Address.newBuilder().setSocketAddress(
                    SocketAddress.newBuilder()
                        .setPortValue(listenersConfig.egressPort)
                        .setAddress(listenersConfig.egressHost)
                )
            )

        group.listenersConfig?.egressPort.let {
            val filterChain: FilterChain.Builder = FilterChain.newBuilder()
           if (group.listenersConfig?.useTransparentProxy == true) {
                filterChain.setFilterChainMatch(
                    FilterChainMatch.newBuilder()
                        .setDestinationPort(UInt32Value.of(group.listenersConfig!!.egressPort))
                )
                listener.setTrafficDirection(TrafficDirection.OUTBOUND).useOriginalDst = BoolValue.of(true)
            }
            listener.addFilterChains(filterChain.addFilters(createEgressFilter(group, globalSnapshot)).build())
        }
        return listener.build()
    }

    private fun createIngressListener(
        group: Group,
        listenersConfig: ListenersConfig,
        globalSnapshot: GlobalSnapshot
    ): Listener {
        val insecureIngressChain = createIngressFilterChain(group, globalSnapshot)
        val securedIngressChain = if (listenersConfig.hasStaticSecretsDefined) {
            createSecuredIngressFilterChain(group, globalSnapshot)
        } else null

        val listener = Listener.newBuilder()
            .setName("ingress_listener")
            .setAddress(
                Address.newBuilder().setSocketAddress(
                    SocketAddress.newBuilder()
                        .setPortValue(listenersConfig.ingressPort)
                        .setAddress(listenersConfig.ingressHost)
                )
            )

        if (securedIngressChain != null) {
            listener.addListenerFilters(tlsInspectorFilter)
        }

        listOfNotNull(securedIngressChain, insecureIngressChain).forEach {
            listener.addFilterChains(it.build())
        }

        return listener.build()
    }

    private fun createEgressVirtualListeners(group: Group, globalSnapshot: GlobalSnapshot): List<Listener> {
        val tcpProxy = group.proxySettings.outgoing.getDomainDependencies().filter {
            it.useSsl()
        }.groupBy(
            { it.getPort() }, { it }
        ).toMap()

        val httpProxyPorts = (group.proxySettings.outgoing.getDomainDependencies() +
            group.proxySettings.outgoing.getServiceDependencies() +
            group.proxySettings.outgoing.getTagDependencies()).filter {
            !it.useSsl()
        }
            .map { it.getPort() }
            .toMutableSet()

        if (group.proxySettings.outgoing.allServicesDependencies) {
            httpProxyPorts.add(DEFAULT_HTTP_PORT)
        }

        return createEgressTcpProxyVirtualListener(tcpProxy) +
            createEgressHttpProxyVirtualListener(httpProxyPorts, group, globalSnapshot)
    }

    private fun createEgressFilter(
        group: Group,
        globalSnapshot: GlobalSnapshot
    ): Filter = Filter.newBuilder()
        .setName("envoy.filters.network.http_connection_manager")
        .setTypedConfig(
            ProtobufAny.pack(
                httpConnectionManagerFactory.createFilter(
                    group,
                    globalSnapshot,
                    egressFilters,
                    routeConfigName = "default_routes",
                    statPrefix = "egress_http",
                    initialFetchTimeout = egressRdsInitialFetchTimeout,
                    direction = HttpConnectionManagerFactory.Direction.EGRESS
                )
            )
        )
        .build()

    private fun createSecuredIngressFilterChain(
        group: Group,
        globalSnapshot: GlobalSnapshot
    ): FilterChain.Builder {
        val filterChain = createIngressFilterChain(group, globalSnapshot, TransportProtocol.TLS)
        val transportSocket = if (snapshotProperties.tcpDumpsEnabled) {
            downstreamTransportSocketWithTap
        } else {
            downstreamTlsTransportSocket
        }
        filterChain.transportSocket = transportSocket
        return filterChain
    }

    private fun createIngressFilterChain(
        group: Group,
        globalSnapshot: GlobalSnapshot,
        transportProtocol: TransportProtocol = TransportProtocol.RAW_BUFFER
    ): FilterChain.Builder {
        val statPrefix = when (transportProtocol) {
            TransportProtocol.RAW_BUFFER -> "ingress_http"
            TransportProtocol.TLS -> "ingress_https"
        }
        return FilterChain.newBuilder()
            .setFilterChainMatch(transportProtocol.filterChainMatch)
            .addFilters(createIngressFilter(group, globalSnapshot, statPrefix))
    }

    private fun createIngressFilter(
        group: Group,
        globalSnapshot: GlobalSnapshot,
        statPrefix: String
    ): Filter {
        return Filter.newBuilder()
            .setName("envoy.filters.network.http_connection_manager")
            .setTypedConfig(
                ProtobufAny.pack(
                    httpConnectionManagerFactory.createFilter(
                        group,
                        globalSnapshot,
                        ingressFilters,
                        routeConfigName = "ingress_secured_routes",
                        statPrefix = statPrefix,
                        initialFetchTimeout = ingressRdsInitialFetchTimeout,
                        direction = HttpConnectionManagerFactory.Direction.INGRESS
                    )
                )
            )
            .build()
    }

    private fun createEgressHttpProxyVirtualListener(
        portAndDomains: Set<Int>,
        group: Group,
        globalSnapshot: GlobalSnapshot
    ): List<Listener> {
        return portAndDomains.map { port ->
            Listener.newBuilder()
                .setName("$DOMAIN_PROXY_LISTENER_ADDRESS:$port")
                .setAddress(
                    Address.newBuilder().setSocketAddress(
                        SocketAddress.newBuilder()
                            .setPortValue(port)
                            .setAddress(DOMAIN_PROXY_LISTENER_ADDRESS)
                    )
                )
                .addFilterChains(createHttpProxyFilterChainForDomains(group, port, globalSnapshot))
                .setTrafficDirection(TrafficDirection.OUTBOUND)
                .setDeprecatedV1(
                    Listener.DeprecatedV1.newBuilder()
                        .setBindToPort(BoolValue.of(false))
                )
                .addListenerFilters(tlsInspectorFilter)
                .addListenerFilters(httpInspectorFilter)
                .build()
        }
    }

    private fun createHttpProxyFilterChainForDomains(
        group: Group,
        port: Int,
        globalSnapshot: GlobalSnapshot
    ): FilterChain {
        val filterChainMatch = FilterChainMatch.newBuilder().setTransportProtocol("raw_buffer")
        val filter = Filter.newBuilder()
            .setName("envoy.filters.network.http_connection_manager")
            .setTypedConfig(
                com.google.protobuf.Any.pack(
                    httpConnectionManagerFactory.createFilter(
                        group,
                        globalSnapshot,
                        egressFilters,
                        routeConfigName = port.toString(),
                        statPrefix = port.toString(),
                        initialFetchTimeout = egressRdsInitialFetchTimeout,
                        direction = HttpConnectionManagerFactory.Direction.EGRESS

                    )
                )
            )
        return FilterChain.newBuilder().setFilterChainMatch(filterChainMatch).addFilters(filter).build()
    }

    private fun createEgressTcpProxyVirtualListener(portAndDomains: Map<Int, List<DomainDependency>>): List<Listener> {
        return portAndDomains.map {
            Listener.newBuilder()
                .setName("$DOMAIN_PROXY_LISTENER_ADDRESS:${it.key}")
                .setAddress(
                    Address.newBuilder().setSocketAddress(
                        SocketAddress.newBuilder()
                            .setPortValue(it.key)
                            .setAddress(DOMAIN_PROXY_LISTENER_ADDRESS)
                    )
                )
                .addAllFilterChains(it.value.map {
                    tcpProxyFilterFactory.createFilter(
                        clusterName = it.getClusterName(),
                        host = it.getHost(),
                        isSsl = it.useSsl()
                    )
                })
                .setTrafficDirection(TrafficDirection.OUTBOUND)
                .setDeprecatedV1(
                    Listener.DeprecatedV1.newBuilder()
                        .setBindToPort(BoolValue.of(false))
                )
                .addListenerFilters(tlsInspectorFilter)
                .build()
        }.toList()
    }
}
