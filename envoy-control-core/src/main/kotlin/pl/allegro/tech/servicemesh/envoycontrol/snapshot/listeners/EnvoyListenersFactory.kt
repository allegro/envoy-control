package pl.allegro.tech.servicemesh.envoycontrol.snapshot.listeners

import com.google.protobuf.BoolValue
import com.google.protobuf.Duration
import com.google.protobuf.Struct
import com.google.protobuf.Value
import io.envoyproxy.envoy.api.v2.Listener
import io.envoyproxy.envoy.api.v2.core.Address
import io.envoyproxy.envoy.api.v2.core.AggregatedConfigSource
import io.envoyproxy.envoy.api.v2.core.ApiConfigSource
import io.envoyproxy.envoy.api.v2.core.ConfigSource
import io.envoyproxy.envoy.api.v2.core.GrpcService
import io.envoyproxy.envoy.api.v2.core.Http1ProtocolOptions
import io.envoyproxy.envoy.api.v2.core.SocketAddress
import io.envoyproxy.envoy.api.v2.listener.Filter
import io.envoyproxy.envoy.api.v2.listener.FilterChain
import io.envoyproxy.envoy.config.accesslog.v2.FileAccessLog
import io.envoyproxy.envoy.config.filter.accesslog.v2.AccessLog
import io.envoyproxy.envoy.config.filter.network.http_connection_manager.v2.HttpConnectionManager
import io.envoyproxy.envoy.config.filter.network.http_connection_manager.v2.HttpFilter
import io.envoyproxy.envoy.config.filter.network.http_connection_manager.v2.Rds
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group
import pl.allegro.tech.servicemesh.envoycontrol.groups.ListenersConfig
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.listeners.filters.EnvoyHttpFilters
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotProperties
import com.google.protobuf.Any as ProtobufAny

typealias HttpFilterFactory = (node: Group) -> HttpFilter?

@Suppress("MagicNumber")
class EnvoyListenersFactory(
    snapshotProperties: SnapshotProperties,
    envoyHttpFilters: EnvoyHttpFilters
) {
    private val ingressFilters: List<HttpFilterFactory> = envoyHttpFilters.ingressFilters
    private val egressFilters: List<HttpFilterFactory> = envoyHttpFilters.egressFilters
    private val listenersFactoryProperties = snapshotProperties.dynamicListeners
    private val accessLogTimeFormat = stringValue(listenersFactoryProperties.httpFilters.accessLog.timeFormat)
    private val accessLogMessageFormat = stringValue(listenersFactoryProperties.httpFilters.accessLog.messageFormat)
    private val accessLogLevel = stringValue(listenersFactoryProperties.httpFilters.accessLog.level)
    private val accessLogLogger = stringValue(listenersFactoryProperties.httpFilters.accessLog.logger)
    private val egressRdsInitialFetchTimeout: Duration = durationInSeconds(20)
    private val ingressRdsInitialFetchTimeout: Duration = durationInSeconds(30)

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

    fun createListeners(group: Group): List<Listener> {
        if (group.listenersConfig == null) {
            return listOf()
        }
        val listenersConfig: ListenersConfig = group.listenersConfig!!
        val ingressListener = createIngressListener(group, listenersConfig)
        val egressListener = createEgressListener(group, listenersConfig)

        return listOf(ingressListener, egressListener)
    }

    private fun createEgressListener(group: Group, listenersConfig: ListenersConfig): Listener {
        return Listener.newBuilder()
                .setName("egress_listener")
                .setAddress(
                        Address.newBuilder().setSocketAddress(
                                SocketAddress.newBuilder()
                                        .setPortValue(listenersConfig.egressPort)
                                        .setAddress(listenersConfig.egressHost)
                        )
                )
                .addFilterChains(createEgressFilterChain(group, listenersConfig))
                .build()
    }

    private fun createIngressListener(group: Group, listenersConfig: ListenersConfig): Listener {
        return Listener.newBuilder()
                .setName("ingress_listener")
                .setAddress(
                        Address.newBuilder().setSocketAddress(
                                SocketAddress.newBuilder()
                                        .setPortValue(listenersConfig.ingressPort)
                                        .setAddress(listenersConfig.ingressHost)
                        )
                )
                .addFilterChains(createIngressFilterChain(group, listenersConfig))
                .build()
    }

    private fun createIngressFilterChain(group: Group, listenersConfig: ListenersConfig): FilterChain {
        return FilterChain.newBuilder()
                .addFilters(createIngressFilter(group, listenersConfig))
                .build()
    }

    private fun createEgressFilterChain(group: Group, listenersConfig: ListenersConfig): FilterChain {
        return FilterChain.newBuilder()
                .addFilters(createEgressFilter(group, listenersConfig))
                .build()
    }

    private fun createEgressFilter(group: Group, listenersConfig: ListenersConfig): Filter {
        val connectionManagerBuilder = HttpConnectionManager.newBuilder()
                .setStatPrefix("egress_http")
                .setRds(egressRds(group.ads))
                .setHttpProtocolOptions(egressHttp1ProtocolOptions())

        return createFilter(connectionManagerBuilder, egressFilters, group, "egress", listenersConfig)
    }

    private fun createFilter(
        connectionManagerBuilder: HttpConnectionManager.Builder,
        filterFactories: List<HttpFilterFactory>,
        group: Group,
        accessLogType: String,
        listenersConfig: ListenersConfig
    ): Filter {
        filterFactories.forEach {
            val filter = it(group)
            if (filter != null) {
                connectionManagerBuilder.addHttpFilters(filter)
            }
        }

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

    private fun egressRds(ads: Boolean): Rds {
        val configSource = ConfigSource.newBuilder()
                .setInitialFetchTimeout(egressRdsInitialFetchTimeout)

        if (ads) {
            configSource.setAds(AggregatedConfigSource.getDefaultInstance())
        } else {
            configSource.setApiConfigSource(defaultApiConfigSource)
        }

        return Rds.newBuilder()
                .setRouteConfigName("default_routes")
                .setConfigSource(
                        configSource.build()
                )
                .build()
    }

    private fun createIngressFilter(group: Group, listenersConfig: ListenersConfig): Filter {
        val ingressHttp = HttpConnectionManager.newBuilder()
                .setStatPrefix("ingress_http")
                .setUseRemoteAddress(boolValue(listenersConfig.useRemoteAddress))
                .setDelayedCloseTimeout(durationInSeconds(0))
                .setCodecType(HttpConnectionManager.CodecType.AUTO)
                .setRds(ingressRds(group.ads))
                .setHttpProtocolOptions(ingressHttp1ProtocolOptions(group.serviceName))

        if (listenersConfig.useRemoteAddress) {
            ingressHttp.setXffNumTrustedHops(listenersFactoryProperties.httpFilters.ingressXffNumTrustedHops)
        }

        return createFilter(ingressHttp, ingressFilters, group, "ingress", listenersConfig)
    }

    private fun ingressHttp1ProtocolOptions(serviceName: String): Http1ProtocolOptions? {
        return Http1ProtocolOptions.newBuilder()
                .setAcceptHttp10(true)
                .setDefaultHostForHttp10(serviceName)
                .build()
    }

    private fun ingressRds(ads: Boolean): Rds {
        val configSource = ConfigSource.newBuilder()
                .setInitialFetchTimeout(ingressRdsInitialFetchTimeout)

        if (ads) {
            configSource.setAds(AggregatedConfigSource.getDefaultInstance())
        } else {
            configSource.setApiConfigSource(defaultApiConfigSource)
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
