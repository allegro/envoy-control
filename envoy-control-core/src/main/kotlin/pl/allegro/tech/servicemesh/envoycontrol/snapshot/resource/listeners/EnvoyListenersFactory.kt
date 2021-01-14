package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners

import com.google.protobuf.BoolValue
import com.google.protobuf.Duration
import com.google.protobuf.Struct
import com.google.protobuf.Value
import com.google.protobuf.util.Durations
import io.envoyproxy.envoy.config.accesslog.v3.AccessLog
import io.envoyproxy.envoy.config.accesslog.v3.AccessLogFilter
import io.envoyproxy.envoy.config.accesslog.v3.ComparisonFilter
import io.envoyproxy.envoy.config.accesslog.v3.StatusCodeFilter
import io.envoyproxy.envoy.config.core.v3.Address
import io.envoyproxy.envoy.config.core.v3.AggregatedConfigSource
import io.envoyproxy.envoy.config.core.v3.ApiConfigSource
import io.envoyproxy.envoy.config.core.v3.ApiVersion
import io.envoyproxy.envoy.config.core.v3.ConfigSource
import io.envoyproxy.envoy.config.core.v3.GrpcService
import io.envoyproxy.envoy.config.core.v3.Http1ProtocolOptions
import io.envoyproxy.envoy.config.core.v3.HttpProtocolOptions
import io.envoyproxy.envoy.config.core.v3.RuntimeUInt32
import io.envoyproxy.envoy.config.core.v3.SocketAddress
import io.envoyproxy.envoy.config.core.v3.TransportSocket
import io.envoyproxy.envoy.config.listener.v3.Filter
import io.envoyproxy.envoy.config.listener.v3.FilterChain
import io.envoyproxy.envoy.config.listener.v3.FilterChainMatch
import io.envoyproxy.envoy.config.listener.v3.Listener
import io.envoyproxy.envoy.config.listener.v3.ListenerFilter
import io.envoyproxy.envoy.extensions.access_loggers.file.v3.FileAccessLog
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.Rds
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CommonTlsContext
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.DownstreamTlsContext
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.SdsSecretConfig
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.TlsParameters
import pl.allegro.tech.servicemesh.envoycontrol.groups.AccessLogFilterSettings
import pl.allegro.tech.servicemesh.envoycontrol.groups.CommunicationMode
import pl.allegro.tech.servicemesh.envoycontrol.groups.CommunicationMode.ADS
import pl.allegro.tech.servicemesh.envoycontrol.groups.CommunicationMode.XDS
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group
import pl.allegro.tech.servicemesh.envoycontrol.groups.ListenersConfig
import pl.allegro.tech.servicemesh.envoycontrol.groups.ResourceVersion
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.GlobalSnapshot
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.config.LocalReplyConfigFactory
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters.EnvoyHttpFilters
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.util.StatusCodeFilterSettings
import com.google.protobuf.Any as ProtobufAny

typealias HttpFilterFactory = (node: Group, snapshot: GlobalSnapshot) -> HttpFilter?

@Suppress("MagicNumber")
class EnvoyListenersFactory(
    snapshotProperties: SnapshotProperties,
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
    private val localReplyConfig = LocalReplyConfigFactory(
        snapshotProperties.dynamicListeners.localReplyMapper
    ).configuration

    private val tlsProperties = snapshotProperties.incomingPermissions.tlsAuthentication
    private val requireClientCertificate = BoolValue.of(tlsProperties.requireClientCertificate)

    private val downstreamTlsContext = DownstreamTlsContext.newBuilder()
            .setRequireClientCertificate(requireClientCertificate)
            .setCommonTlsContext(CommonTlsContext.newBuilder()
                    .setTlsParams(TlsParameters.newBuilder()
                            .setTlsMinimumProtocolVersion(tlsProperties.protocol.minimumVersion)
                            .setTlsMaximumProtocolVersion(tlsProperties.protocol.maximumVersion)
                            .addAllCipherSuites(tlsProperties.protocol.cipherSuites)
                            .build())
                    .addTlsCertificateSdsSecretConfigs(SdsSecretConfig.newBuilder()
                            .setName(tlsProperties.tlsCertificateSecretName)
                            .build()
                    )
                    .setValidationContextSdsSecretConfig(SdsSecretConfig.newBuilder()
                            .setName(tlsProperties.validationContextSecretName)
                            .build()
                    )
            )

    private val downstreamTlsTransportSocket = TransportSocket.newBuilder()
            .setName("envoy.transport_sockets.tls")
            .setTypedConfig(ProtobufAny.pack(downstreamTlsContext.build()))
            .build()

    private val tlsInspectorFilter = ListenerFilter.newBuilder().setName("envoy.filters.listener.tls_inspector").build()

    private enum class TransportProtocol(
        value: String,
        val filterChainMatch: FilterChainMatch = FilterChainMatch.newBuilder().setTransportProtocol(value).build()
    ) {
        RAW_BUFFER("raw_buffer"),
        TLS("tls")
    }

    val defaultApiConfigSourceV2: ApiConfigSource = apiConfigSource(ApiVersion.V2)
    val defaultApiConfigSourceV3: ApiConfigSource = apiConfigSource(ApiVersion.V3)

    fun apiConfigSource(apiVersion: ApiVersion): ApiConfigSource {
        return ApiConfigSource.newBuilder()
                .setApiType(ApiConfigSource.ApiType.GRPC)
                .setTransportApiVersion(apiVersion)
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

        if (securedIngressChain != null) {
            listener.addListenerFilters(tlsInspectorFilter)
        }

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
        val statPrefix = when (transportProtocol) {
            TransportProtocol.RAW_BUFFER -> "ingress_http"
            TransportProtocol.TLS -> "ingress_https"
        }
        return FilterChain.newBuilder()
                .setFilterChainMatch(transportProtocol.filterChainMatch)
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
                .setRds(egressRds(group.communicationMode, group.version))
                .setHttpProtocolOptions(egressHttp1ProtocolOptions())
                .setPreserveExternalRequestId(listenersConfig.preserveExternalRequestId)
                .setGenerateRequestId(boolValue(listenersConfig.generateRequestId))

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
            connectionManagerBuilder.addAccessLog(
                accessLog(listenersConfig.accessLogPath, accessLogType, listenersConfig.accessLogFilterSettings)
            )
        }

        if (listenersFactoryProperties.localReplyMapper.enabled) {
            connectionManagerBuilder.localReplyConfig = localReplyConfig
        }

        return Filter.newBuilder()
                .setName("envoy.filters.network.http_connection_manager")
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

    private fun egressRds(communicationMode: CommunicationMode, version: ResourceVersion): Rds {
        val configSource = ConfigSource.newBuilder()
                .setInitialFetchTimeout(egressRdsInitialFetchTimeout)

        if (version == ResourceVersion.V3) {
            configSource.setResourceApiVersion(ApiVersion.V3)
        }

        when (communicationMode) {
            ADS -> configSource.setAds(AggregatedConfigSource.getDefaultInstance())
            XDS -> setXdsConfigSourceVersion(version, configSource)
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
                .setGenerateRequestId(boolValue(listenersConfig.generateRequestId))
                .setPreserveExternalRequestId(listenersConfig.preserveExternalRequestId)
                .setDelayedCloseTimeout(durationInSeconds(0))
                .setCommonHttpProtocolOptions(httpProtocolOptions)
                .setCodecType(HttpConnectionManager.CodecType.AUTO)
                .setRds(ingressRds(group.communicationMode, group.version))
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

    private fun ingressRds(communicationMode: CommunicationMode, version: ResourceVersion): Rds {
        val configSource = ConfigSource.newBuilder()
                .setInitialFetchTimeout(ingressRdsInitialFetchTimeout)

        if (version == ResourceVersion.V3) {
            configSource.setResourceApiVersion(ApiVersion.V3)
        }

        when (communicationMode) {
            ADS -> configSource.setAds(AggregatedConfigSource.getDefaultInstance())
            XDS -> setXdsConfigSourceVersion(version, configSource)
        }

        return Rds.newBuilder()
                .setRouteConfigName("ingress_secured_routes")
                .setConfigSource(configSource.build())
                .build()
    }

    private fun setXdsConfigSourceVersion(version: ResourceVersion, configSource: ConfigSource.Builder) {
        if (version == ResourceVersion.V3) {
            configSource.apiConfigSource = defaultApiConfigSourceV3
        } else {
            configSource.apiConfigSource = defaultApiConfigSourceV2
        }
    }

    fun AccessLog.Builder.buildFromSettings(settings: StatusCodeFilterSettings) {
        this.setFilter(
                AccessLogFilter.newBuilder().setStatusCodeFilter(
                        StatusCodeFilter.newBuilder()
                                .setComparison(
                                        ComparisonFilter.newBuilder()
                                                .setOp(settings.comparisonOperator)
                                                .setValue(
                                                        RuntimeUInt32.newBuilder()
                                                                .setDefaultValue(settings.comparisonCode)
                                                                .setRuntimeKey("access_log_filter_http_code")
                                                                .build()
                                                )
                                                .build()
                                )
                                .build()
                )
        )
    }

    private fun accessLog(
        accessLogPath: String,
        accessLogType: String,
        accessLogFilterSettings: AccessLogFilterSettings?
    ): AccessLog {
        val builder = AccessLog.newBuilder().setName("envoy.file_access_log")

        accessLogFilterSettings?.let { settings ->
            settings.statusCodeFilterSettings?.let {
                builder.buildFromSettings(it)
            }
        }

        return builder.setTypedConfig(
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
