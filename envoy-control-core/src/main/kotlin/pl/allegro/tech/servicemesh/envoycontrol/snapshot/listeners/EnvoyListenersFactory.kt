package pl.allegro.tech.servicemesh.envoycontrol.snapshot.listeners

import com.google.protobuf.BoolValue
import com.google.protobuf.Duration
import com.google.protobuf.Struct
import com.google.protobuf.Value
import com.google.protobuf.util.Durations
import io.envoyproxy.envoy.api.v2.Listener
import io.envoyproxy.envoy.api.v2.auth.CommonTlsContext
import io.envoyproxy.envoy.api.v2.auth.DownstreamTlsContext
import io.envoyproxy.envoy.api.v2.auth.SdsSecretConfig
import io.envoyproxy.envoy.api.v2.auth.TlsParameters
import io.envoyproxy.envoy.api.v2.core.Address
import io.envoyproxy.envoy.api.v2.core.AggregatedConfigSource
import io.envoyproxy.envoy.api.v2.core.ApiConfigSource
import io.envoyproxy.envoy.api.v2.core.ConfigSource
import io.envoyproxy.envoy.api.v2.core.GrpcService
import io.envoyproxy.envoy.api.v2.core.Http1ProtocolOptions
import io.envoyproxy.envoy.api.v2.core.HttpProtocolOptions
import io.envoyproxy.envoy.api.v2.core.SocketAddress
import io.envoyproxy.envoy.api.v2.core.TransportSocket
import io.envoyproxy.envoy.api.v2.listener.Filter
import io.envoyproxy.envoy.api.v2.listener.FilterChain
import io.envoyproxy.envoy.api.v2.listener.FilterChainMatch
import io.envoyproxy.envoy.config.accesslog.v2.FileAccessLog
import io.envoyproxy.envoy.config.filter.accesslog.v2.AccessLog
import io.envoyproxy.envoy.config.filter.network.http_connection_manager.v2.HttpConnectionManager
import io.envoyproxy.envoy.config.filter.network.http_connection_manager.v2.HttpFilter
import io.envoyproxy.envoy.config.filter.network.http_connection_manager.v2.Rds
import pl.allegro.tech.servicemesh.envoycontrol.groups.CommunicationMode
import pl.allegro.tech.servicemesh.envoycontrol.groups.CommunicationMode.ADS
import pl.allegro.tech.servicemesh.envoycontrol.groups.CommunicationMode.XDS
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group
import pl.allegro.tech.servicemesh.envoycontrol.groups.ListenersConfig
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.GlobalSnapshot
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.listeners.filters.EnvoyHttpFilters
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotProperties
import com.google.protobuf.Any as ProtobufAny

typealias HttpFilterFactory = (node: Group, snapshot: GlobalSnapshot) -> HttpFilter?

@Suppress("MagicNumber")
class EnvoyListenersFactory(
    private val snapshotProperties: SnapshotProperties,
    envoyHttpFilters: EnvoyHttpFilters
) {
    private val ingressFilters: List<HttpFilterFactory> = envoyHttpFilters.ingressFilters
    private val egressFilters: List<HttpFilterFactory> = envoyHttpFilters.egressFilters
    private val listenersFactoryProperties = snapshotProperties.dynamicListeners
    private val localServiceProperties = snapshotProperties.localService
    private val accessLogTimeFormat = stringValue(listenersFactoryProperties.httpFilters.accessLog.timeFormat)
    private val accessLogMessageFormat = stringValue(listenersFactoryProperties.httpFilters.accessLog.messageFormat)
    private val accessLogLevel = stringValue(listenersFactoryProperties.httpFilters.accessLog.level)
    private val accessLogLogger = stringValue(listenersFactoryProperties.httpFilters.accessLog.logger)
    private val egressRdsInitialFetchTimeout: Duration = durationInSeconds(20)
    private val ingressRdsInitialFetchTimeout: Duration = durationInSeconds(30)

    private val tlsAuthenticationProperties = snapshotProperties.incomingPermissions.tlsAuthentication
    private val requireClientCertificate = BoolValue.of(tlsAuthenticationProperties.requireClientCertificate)
    private val downstreamTlsContext = DownstreamTlsContext.newBuilder()
            .setRequireClientCertificate(requireClientCertificate)
            .setCommonTlsContext(CommonTlsContext.newBuilder()
                    .setTlsParams(TlsParameters.newBuilder()
                            .setTlsMinimumProtocolVersion(TlsParameters.TlsProtocol.TLSv1_2)
                            .setTlsMaximumProtocolVersion(TlsParameters.TlsProtocol.TLSv1_2)
                            .addAllCipherSuites(listOf(
                                    "ECDHE-ECDSA-AES128-GCM-SHA256",
                                    "ECDHE-RSA-AES128-GCM-SHA256"))
                            .build())
                    .addTlsCertificateSdsSecretConfigs(SdsSecretConfig.newBuilder()
                            .setName(tlsAuthenticationProperties.tlsCertificateSecretName)
                            .build()
                    )
                    .setValidationContextSdsSecretConfig(SdsSecretConfig.newBuilder()
                            .setName(tlsAuthenticationProperties.validationContextConfigName)
                            .build()
                    )
            )

    private val downstreamTlsTransportSocket = TransportSocket.newBuilder()
            .setName("envoy.transport_sockets.tls")
            .setTypedConfig(ProtobufAny.pack(downstreamTlsContext.build()))
            .build()

    private enum class TransportProtocol(val value: String) {
        RAW_BUFFER("raw_buffer"),
        TLS("tls")
    }

    val defaultApiConfigSource: ApiConfigSource = apiConfigSource()

    fun apiConfigSource(): ApiConfigSource {
        return ApiConfigSource.newBuilder()
                .setApiType(ApiConfigSource.ApiType.GRPC)
                .addGrpcServices(GrpcService.newBuilder()
                        .setEnvoyGrpc(
                                GrpcService.EnvoyGrpc.newBuilder()
                                        .setClusterName("envoy-control-xds")
                        )
                ).build()
    }

    fun createListeners(group: Group, globalSnapshot: GlobalSnapshot): List<Listener> {
        if (group.listenersConfig == null) {
            return listOf()
        }
        val listenersConfig: ListenersConfig = group.listenersConfig!!
        val ingressListener = createIngressListener(group, listenersConfig, globalSnapshot)
        val egressListener = createEgressListener(group, listenersConfig, globalSnapshot)

        return listOf(ingressListener, egressListener)
    }

    private fun createEgressListener(
        group: Group,
        listenersConfig: ListenersConfig,
        globalSnapshot: GlobalSnapshot
    ): Listener {
        return Listener.newBuilder()
                .setName("egress_listener")
                .setAddress(
                        Address.newBuilder().setSocketAddress(
                                SocketAddress.newBuilder()
                                        .setPortValue(listenersConfig.egressPort)
                                        .setAddress(listenersConfig.egressHost)
                        )
                )
                .addFilterChains(createEgressFilterChain(group, listenersConfig, globalSnapshot))
                .build()
    }

    private fun createIngressListener(
        group: Group,
        listenersConfig: ListenersConfig,
        globalSnapshot: GlobalSnapshot
    ): Listener {
        val insecureIngressChain = createIngressFilterChain(group, listenersConfig, globalSnapshot)
        val securedIngressChain = if (listenersConfig.hasStaticSecretsDefined) {
            createSecuredIngressFilterChain(group, listenersConfig, globalSnapshot)
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

        listOfNotNull(securedIngressChain, insecureIngressChain).forEach {
            listener.addFilterChains(it.build())
        }

        return listener.build()
    }

    private fun createIngressFilterChain(
        group: Group,
        listenersConfig: ListenersConfig,
        globalSnapshot: GlobalSnapshot,
        transportProtocol: TransportProtocol = TransportProtocol.RAW_BUFFER
    ): FilterChain.Builder {
        val statPrefix = if (transportProtocol == TransportProtocol.RAW_BUFFER) "ingress_http" else "ingress_https"
        return FilterChain.newBuilder()
                .setFilterChainMatch(FilterChainMatch.newBuilder().setTransportProtocol(transportProtocol.value))
                .addFilters(createIngressFilter(group, listenersConfig, globalSnapshot, statPrefix))
    }

    private fun createSecuredIngressFilterChain(
        group: Group,
        listenersConfig: ListenersConfig,
        globalSnapshot: GlobalSnapshot
    ): FilterChain.Builder {
        val filterChain = createIngressFilterChain(group, listenersConfig, globalSnapshot, TransportProtocol.TLS)
        filterChain.setTransportSocket(downstreamTlsTransportSocket)
        return filterChain
    }

    private fun createEgressFilterChain(
        group: Group,
        listenersConfig: ListenersConfig,
        globalSnapshot: GlobalSnapshot
    ): FilterChain {
        return FilterChain.newBuilder()
                .addFilters(createEgressFilter(group, listenersConfig, globalSnapshot))
                .build()
    }

    private fun createEgressFilter(
        group: Group,
        listenersConfig: ListenersConfig,
        globalSnapshot: GlobalSnapshot
    ): Filter {
        val connectionManagerBuilder = HttpConnectionManager.newBuilder()
                .setStatPrefix("egress_http")
                .setRds(egressRds(group.communicationMode))
                .setHttpProtocolOptions(egressHttp1ProtocolOptions())

        addHttpFilters(connectionManagerBuilder, egressFilters, group, globalSnapshot)

        return createFilter(connectionManagerBuilder, "egress", listenersConfig)
    }

    private fun addHttpFilters(
        connectionManagerBuilder: HttpConnectionManager.Builder,
        filterFactories: List<HttpFilterFactory>,
        group: Group,
        globalSnapshot: GlobalSnapshot
    ) {
        filterFactories.forEach { filterFactory ->
            val filter = filterFactory(group, globalSnapshot)
            if (filter != null) {
                connectionManagerBuilder.addHttpFilters(filter)
            }
        }
    }

    private fun createFilter(
        connectionManagerBuilder: HttpConnectionManager.Builder,
        accessLogType: String,
        listenersConfig: ListenersConfig
    ): Filter {
        if (listenersConfig.accessLogEnabled) {
            connectionManagerBuilder.addAccessLog(accessLog(listenersConfig.accessLogPath, accessLogType))
        }

        return Filter.newBuilder()
                .setName("envoy.http_connection_manager")
                .setTypedConfig(ProtobufAny.pack(
                        connectionManagerBuilder.build()
                ))
                .build()
    }

    private fun egressHttp1ProtocolOptions(): Http1ProtocolOptions? {
        return Http1ProtocolOptions.newBuilder()
                .setAllowAbsoluteUrl(boolValue(true))
                .build()
    }

    private fun egressRds(communicationMode: CommunicationMode): Rds {
        val configSource = ConfigSource.newBuilder()
                .setInitialFetchTimeout(egressRdsInitialFetchTimeout)

        when (communicationMode) {
            ADS -> configSource.setAds(AggregatedConfigSource.getDefaultInstance())
            XDS -> configSource.setApiConfigSource(defaultApiConfigSource)
        }

        return Rds.newBuilder()
                .setRouteConfigName("default_routes")
                .setConfigSource(
                        configSource.build()
                )
                .build()
    }

    private fun createIngressFilter(
        group: Group,
        listenersConfig: ListenersConfig,
        globalSnapshot: GlobalSnapshot,
        statPrefix: String
    ): Filter {
        val connectionIdleTimeout = group.proxySettings.incoming.timeoutPolicy.connectionIdleTimeout
            ?: Durations.fromMillis(localServiceProperties.connectionIdleTimeout.toMillis())
        val httpProtocolOptions = HttpProtocolOptions.newBuilder().setIdleTimeout(connectionIdleTimeout).build()
        val connectionManagerBuilder = HttpConnectionManager.newBuilder()
                .setStatPrefix(statPrefix)
                .setUseRemoteAddress(boolValue(listenersConfig.useRemoteAddress))
                .setDelayedCloseTimeout(durationInSeconds(0))
                .setCommonHttpProtocolOptions(httpProtocolOptions)
                .setCodecType(HttpConnectionManager.CodecType.AUTO)
                .setRds(ingressRds(group.communicationMode))
                .setHttpProtocolOptions(ingressHttp1ProtocolOptions(group.serviceName))

        if (listenersConfig.useRemoteAddress) {
            connectionManagerBuilder.setXffNumTrustedHops(
                    listenersFactoryProperties.httpFilters.ingressXffNumTrustedHops
            )
        }

        addHttpFilters(connectionManagerBuilder, ingressFilters, group, globalSnapshot)

        return createFilter(connectionManagerBuilder, "ingress", listenersConfig)
    }

    private fun ingressHttp1ProtocolOptions(serviceName: String): Http1ProtocolOptions? {
        return Http1ProtocolOptions.newBuilder()
                .setAcceptHttp10(true)
                .setDefaultHostForHttp10(serviceName)
                .build()
    }

    private fun ingressRds(communicationMode: CommunicationMode): Rds {
        val configSource = ConfigSource.newBuilder()
                .setInitialFetchTimeout(ingressRdsInitialFetchTimeout)

        when (communicationMode) {
            ADS -> configSource.setAds(AggregatedConfigSource.getDefaultInstance())
            XDS -> configSource.setApiConfigSource(defaultApiConfigSource)
        }

        return Rds.newBuilder()
                .setRouteConfigName("ingress_secured_routes")
                .setConfigSource(configSource.build())
                .build()
    }

    private fun accessLog(accessLogPath: String, accessLogType: String): AccessLog {
        return AccessLog.newBuilder()
                .setName("envoy.file_access_log")
                .setTypedConfig(
                        ProtobufAny.pack(
                                FileAccessLog.newBuilder()
                                        .setPath(accessLogPath)
                                        .setJsonFormat(
                                                Struct.newBuilder()
                                                        .putFields("time", accessLogTimeFormat)
                                                        .putFields("message", accessLogMessageFormat)
                                                        .putFields("level", accessLogLevel)
                                                        .putFields("logger", accessLogLogger)
                                                        .putFields("access_log_type", stringValue(accessLogType))
                                                        .putFields("request_protocol", stringValue("%PROTOCOL%"))
                                                        .putFields("request_method", stringValue("%REQ(:METHOD)%"))
                                                        .putFields("request_authority",
                                                                stringValue("%REQ(:authority)%"))
                                                        .putFields("request_path", stringValue("%REQ(:PATH)%"))
                                                        .putFields("response_code", stringValue("%RESPONSE_CODE%"))
                                                        .putFields("response_flags", stringValue("%RESPONSE_FLAGS%"))
                                                        .putFields("bytes_received", stringValue("%BYTES_RECEIVED%"))
                                                        .putFields("bytes_sent", stringValue("%BYTES_SENT%"))
                                                        .putFields("duration_ms", stringValue("%DURATION%"))
                                                        .putFields("downstream_remote_address",
                                                                stringValue("%DOWNSTREAM_REMOTE_ADDRESS%"))
                                                        .putFields("upstream_host", stringValue("%UPSTREAM_HOST%"))
                                                        .putFields("user_agent", stringValue("%REQ(USER-AGENT)%"))
                                                        .build()
                                        )
                                        .build()
                        )
                )
                .build()
    }

    private fun boolValue(value: Boolean) = BoolValue.newBuilder().setValue(value).build()

    private fun durationInSeconds(value: Long) = Duration.newBuilder().setSeconds(value).build()

    private fun stringValue(value: String) = Value.newBuilder().setStringValue(value).build()
}
