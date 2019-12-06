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
import pl.allegro.tech.servicemesh.envoycontrol.groups.ListenersConfig
import com.google.protobuf.Any as ProtobufAny
import io.envoyproxy.envoy.config.filter.http.header_to_metadata.v2.Config as HeaderToMetadataConfig

@Suppress("MagicNumber")
internal class EnvoyListenersFactory(serviceTagFilter: ServiceTagFilter) {
    private val egressRdsInitialFetchTimeout: Long = 20
    private val ingressRdsInitialFetchTimeout: Long = 30

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
                .addFilterChains(createIngressFilterChain(group.ads, group.serviceName, listenersConfig))
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
                .addFilterChains(createEgressFilterChain(group.ads, group.serviceName, listenersConfig))
                .build()

        return listOf(ingressListener, egressListener)
    }

    private fun createIngressFilterChain(ads: Boolean, serviceName: String, listenersConfig: ListenersConfig): FilterChain {
        return FilterChain.newBuilder()
                .addFilters(createIngressFilter(ads, serviceName, listenersConfig))
                .build()
    }

    private fun createEgressFilterChain(ads: Boolean, serviceName: String, listenersConfig: ListenersConfig): FilterChain {
        return FilterChain.newBuilder()
                .addFilters(createEgressFilter(ads, listenersConfig))
                .build()
    }

    private fun createEgressFilter(ads: Boolean, listenersConfig: ListenersConfig): Filter {
        val egressFilter = HttpConnectionManager.newBuilder()
                .setStatPrefix("egress_http")
                .setRds(egressRds(ads))
                .addHttpFilters(defaultHeaderToMetadataFilter)
                .addHttpFilters(defaultEnvoyRouterHttpFilter)
                .setHttpProtocolOptions(egressHttp1ProtocolOptions())

        if (listenersConfig.accessLogEnabled) {
            egressFilter.addAccessLog(accessLog(listenersConfig.accessLogPath))
        }

        return Filter.newBuilder()
                .setName("envoy.http_connection_manager")
                .setTypedConfig(ProtobufAny.pack(
                        egressFilter.build()
                ))
                .build()
    }

    private fun egressHttp1ProtocolOptions(): Http1ProtocolOptions? {
        return Http1ProtocolOptions.newBuilder()
                .setAllowAbsoluteUrl(boolValue(true))
                .build()
    }

    private val allServiceTagFilters = serviceTagFilter.getFilters()
    private val defaultHeaderToMetadataConfig = headerToMetadataConfig()
    private val defaultHeaderToMetadataFilter = headerToMetadataHttpFilter()

    private fun headerToMetadataHttpFilter(): HttpFilter? {
        return HttpFilter.newBuilder()
                .setName("envoy.filters.http.header_to_metadata")
                .setTypedConfig(ProtobufAny.pack(
                        defaultHeaderToMetadataConfig.build()
                ))
                .build()
    }

    private fun headerToMetadataConfig(): HeaderToMetadataConfig.Builder {
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

        allServiceTagFilters.forEach {
            headerToMetadataConfig.addRequestRules(it)
        }
        return headerToMetadataConfig
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

    val defaultApiConfigSource = ApiConfigSource.newBuilder()
            .setApiType(ApiConfigSource.ApiType.GRPC)
            .addGrpcServices(GrpcService.newBuilder()
                    .setEnvoyGrpc(
                            GrpcService.EnvoyGrpc.newBuilder()
                                    .setClusterName("envoy-control-xds")
                    )
            ).build()

    private fun createIngressFilter(ads: Boolean, serviceName: String, listenersConfig: ListenersConfig): Filter {
        val ingressHttp = HttpConnectionManager.newBuilder()
                .setStatPrefix("ingress_http")
                .setUseRemoteAddress(boolValue(listenersConfig.useRemoteAddress))
                .setXffNumTrustedHops(1)
                .setDelayedCloseTimeout(durationInSeconds(0))
                .setCodecType(HttpConnectionManager.CodecType.AUTO)
                .setRds(ingressRds(ads))
                .addHttpFilters(defaultEnvoyRouterHttpFilter)
                .setHttpProtocolOptions(ingressHttp1ProtocolOptions(serviceName))

        if (listenersConfig.accessLogEnabled) {
            ingressHttp.addAccessLog(accessLog(listenersConfig.accessLogPath))
        }

        return Filter.newBuilder()
                .setName("envoy.http_connection_manager")
                .setTypedConfig(ProtobufAny.pack(
                        ingressHttp.build()
                ))
                .build()
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

    private val defaultEnvoyRouterHttpFilter = envoyRouterHttpFilter()

    private fun envoyRouterHttpFilter() = HttpFilter.newBuilder().setName("envoy.router").build()

    private fun accessLog(accessLogPath: String): AccessLog {
        return AccessLog.newBuilder()
                .setName("envoy.file_access_log")
                .setTypedConfig(
                        ProtobufAny.pack(
                                FileAccessLog.newBuilder()
                                        .setPath(accessLogPath)
                                        .setJsonFormat(
                                                Struct.newBuilder()
                                                        .putFields("time", stringValue("%START_TIME(%FT%T.%3fZ)%"))
                                                        .putFields("message", stringValue("%PROTOCOL% %REQ(:METHOD)% " +
                                                                "%REQ(:authority)% %REQ(:PATH)% " +
                                                                "%DOWNSTREAM_REMOTE_ADDRESS% -> %UPSTREAM_HOST%"))
                                                        .putFields("level", stringValue("TRACE"))
                                                        .putFields("logger", stringValue("envoy.AccessLog"))
                                                        .putFields("access_log_type", stringValue("ingress"))
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
