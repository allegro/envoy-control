package pl.allegro.tech.servicemesh.envoycontrol.snapshot

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
import com.google.protobuf.Any as ProtobufAny
import io.envoyproxy.envoy.config.filter.http.header_to_metadata.v2.Config as HeaderToMetadataConfig

typealias HttpFilterFactory = (node: Group) -> HttpFilter?

@Suppress("MagicNumber")
class EnvoyListenersFactory(
    private val listenersFactoryProperties: ListenersFactoryProperties,
    private val ingressFilters: List<HttpFilterFactory> = listOf(),
    private val egressFilters: List<HttpFilterFactory> = listOf()
) {
    private val accessLogTimeFormat = stringValue(listenersFactoryProperties.httpFilters.accessLog.timeFormat)
    private val accessLogMessageFormat = stringValue(listenersFactoryProperties.httpFilters.accessLog.messageFormat)
    private val accessLogLevel = stringValue(listenersFactoryProperties.httpFilters.accessLog.level)
    private val accessLogLogger = stringValue(listenersFactoryProperties.httpFilters.accessLog.logger)

    companion object {
        private const val egressRdsInitialFetchTimeout: Long = 20
        private const val ingressRdsInitialFetchTimeout: Long = 30

        val defaultServiceTagFilterRules = serviceTagFilterRules()
        val defaultHeaderToMetadataConfig = headerToMetadataConfig(defaultServiceTagFilterRules)
        val defaultHeaderToMetadataFilter = { _: Group -> headerToMetadataHttpFilter(defaultHeaderToMetadataConfig) }
        val defaultEnvoyRouterHttpFilter = { _: Group -> envoyRouterHttpFilter() }
        val defaultApiConfigSource: ApiConfigSource = apiConfigSource()
        val defaultEgressFilters = listOf(defaultHeaderToMetadataFilter, defaultEnvoyRouterHttpFilter)
        val defaultIngressFilters = listOf(defaultEnvoyRouterHttpFilter)

        private fun envoyRouterHttpFilter(): HttpFilter = HttpFilter.newBuilder().setName("envoy.router").build()

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

        fun headerToMetadataHttpFilter(headerToMetadataConfig: HeaderToMetadataConfig.Builder): HttpFilter {
            return HttpFilter.newBuilder()
                    .setName("envoy.filters.http.header_to_metadata")
                    .setTypedConfig(ProtobufAny.pack(
                            headerToMetadataConfig.build()
                    ))
                    .build()
        }

        fun headerToMetadataConfig(rules: List<HeaderToMetadataConfig.Rule>): HeaderToMetadataConfig.Builder {
            val headerToMetadataConfig = HeaderToMetadataConfig.newBuilder()
                    .addRequestRules(
                            HeaderToMetadataConfig.Rule.newBuilder()
                                    .setHeader("x-canary")
                                    .setRemove(false)
                                    .setOnHeaderPresent(
                                            HeaderToMetadataConfig.KeyValuePair.newBuilder()
                                                    .setKey("canary")
                                                    .setMetadataNamespace("envoy.lb")
                                                    .setType(HeaderToMetadataConfig.ValueType.STRING)
                                                    .build()
                                    )
                                    .build()
                    )

            rules.forEach {
                headerToMetadataConfig.addRequestRules(it)
            }

            return headerToMetadataConfig
        }

        fun serviceTagFilterRules(
            header: String = "x-service-tag",
            tag: String = "tag"
        ): List<HeaderToMetadataConfig.Rule> {
            return listOf(HeaderToMetadataConfig.Rule.newBuilder()
                    .setHeader(header)
                    .setRemove(false)
                    .setOnHeaderPresent(
                            HeaderToMetadataConfig.KeyValuePair.newBuilder()
                                    .setKey(tag)
                                    .setMetadataNamespace("envoy.lb")
                                    .setType(HeaderToMetadataConfig.ValueType.STRING)
                                    .build()
                    )
                    .build())
        }
    }

    fun createListeners(group: Group): List<Listener> {
        if (group.listenersConfig == null) {
            return listOf()
        }
        val listenersConfig = group.listenersConfig!!

        val ingressListener = Listener.newBuilder()
                .setName("ingress_listener")
                .setAddress(
                        Address.newBuilder().setSocketAddress(
                                SocketAddress.newBuilder()
                                        .setPortValue(listenersConfig.ingressPort)
                                        .setAddress(listenersConfig.ingressHost)
                        )
                )
                .addFilterChains(createIngressFilterChain(group))
                .build()

        val egressListener = Listener.newBuilder()
                .setName("egress_listener")
                .setAddress(
                        Address.newBuilder().setSocketAddress(
                                SocketAddress.newBuilder()
                                        .setPortValue(listenersConfig.egressPort)
                                        .setAddress(listenersConfig.egressHost)
                        )
                )
                .addFilterChains(createEgressFilterChain(group))
                .build()

        return listOf(ingressListener, egressListener)
    }

    private fun createIngressFilterChain(group: Group): FilterChain {
        return FilterChain.newBuilder()
                .addFilters(createIngressFilter(group))
                .build()
    }

    private fun createEgressFilterChain(group: Group): FilterChain {
        return FilterChain.newBuilder()
                .addFilters(createEgressFilter(group))
                .build()
    }

    private fun createEgressFilter(group: Group): Filter {
        val connectionManagerBuilder = HttpConnectionManager.newBuilder()
                .setStatPrefix("egress_http")
                .setRds(egressRds(group.ads))
                .setHttpProtocolOptions(egressHttp1ProtocolOptions())

        return createFilter(connectionManagerBuilder, egressFilters, group, "egress")
    }

    private fun createFilter(
        connectionManagerBuilder: HttpConnectionManager.Builder,
        filters: List<HttpFilterFactory>,
        group: Group,
        accessLogType: String
    ): Filter {
        filters.forEach {
            val filter = it(group)
            if (filter != null) {
                connectionManagerBuilder.addHttpFilters(filter)
            }
        }

        // checked in EnvoyListenersFactory#createListeners
        if (group.listenersConfig!!.accessLogEnabled) {
            connectionManagerBuilder.addAccessLog(accessLog(group.listenersConfig!!.accessLogPath, accessLogType))
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
                .setInitialFetchTimeout(durationInSeconds(egressRdsInitialFetchTimeout))

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

    private fun createIngressFilter(group: Group): Filter {
        val ingressHttp = HttpConnectionManager.newBuilder()
                .setStatPrefix("ingress_http")
                .setUseRemoteAddress(boolValue(group.listenersConfig!!.useRemoteAddress))
                .setDelayedCloseTimeout(durationInSeconds(0))
                .setCodecType(HttpConnectionManager.CodecType.AUTO)
                .setRds(ingressRds(group.ads))
                .setHttpProtocolOptions(ingressHttp1ProtocolOptions(group.serviceName))

         if (group.listenersConfig!!.useRemoteAddress) {
             ingressHttp.setXffNumTrustedHops(listenersFactoryProperties.httpFilters.ingressXffNumTrustedHops)
         }

        return createFilter(ingressHttp, ingressFilters, group, "ingress")
    }

    private fun ingressHttp1ProtocolOptions(serviceName: String): Http1ProtocolOptions? {
        return Http1ProtocolOptions.newBuilder()
                .setAcceptHttp10(true)
                .setDefaultHostForHttp10(serviceName)
                .build()
    }

    private fun ingressRds(ads: Boolean): Rds {
        val configSource = ConfigSource.newBuilder()
                .setInitialFetchTimeout(durationInSeconds(ingressRdsInitialFetchTimeout))

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
