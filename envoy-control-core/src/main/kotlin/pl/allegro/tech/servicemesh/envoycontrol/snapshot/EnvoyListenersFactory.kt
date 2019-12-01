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

@Suppress("MagicNumber")
internal class EnvoyListenersFactory {
    private val egressRdsInitialFetchTimeout: Long = 20
    private val ingressRdsInitialFetchTimeout: Long = 30

    fun createListeners(group: Group): List<Listener> {
        val ingressListener = Listener.newBuilder()
                .setName("ingress_listener")
                .setAddress(
                        Address.newBuilder().setSocketAddress(
                                SocketAddress.newBuilder().setPortValue(group.ingressPort).setAddress(group.ingressHost)
                        )
                )
                .addFilterChains(createIngressFilterChain(group))
                .build()

        val egressListener = Listener.newBuilder()
                .setName("egress_listener")
                .setAddress(
                        Address.newBuilder().setSocketAddress(
                                SocketAddress.newBuilder().setPortValue(group.egressPort).setAddress(group.egressHost)
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
        val egressFilter = HttpConnectionManager.newBuilder()
                .setStatPrefix("egress_http")
                .setRds(egressRds(group.ads))
                .addHttpFilters(defaultHeaderToMetadataFilter)
                .addHttpFilters(defaultEnvoyRouterHttpFilter)
                .setHttpProtocolOptions(egressHttp1ProtocolOptions())

        if (group.accessLogEnabled) {
            egressFilter.addAccessLog(accessLog(group))
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

    private val defaultHeaderToMetadataFilter = headerToMetadataHttpFilter()

    private fun headerToMetadataHttpFilter(): HttpFilter? {
        return HttpFilter.newBuilder()
                .setName("envoy.filters.http.header_to_metadata")
                .setTypedConfig(ProtobufAny.pack(
                        HeaderToMetadataConfig.newBuilder()
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
                                .addRequestRules(
                                        HeaderToMetadataConfig.Rule.newBuilder()
                                                .setHeader("x-service-tag")
                                                .setRemove(false)
                                                .setOnHeaderPresent(
                                                        HeaderToMetadataConfig.KeyValuePair.newBuilder()
                                                                .setKey("tag")
                                                                .setMetadataNamespace("envoy.lb")
                                                                .setType(HeaderToMetadataConfig.ValueType.STRING)
                                                                .build()
                                                )
                                                .build()
                                )
                                .build()
                ))
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

    val defaultApiConfigSource = ApiConfigSource.newBuilder()
            .setApiType(ApiConfigSource.ApiType.GRPC)
            .addGrpcServices(GrpcService.newBuilder()
                    .setEnvoyGrpc(
                            GrpcService.EnvoyGrpc.newBuilder()
                                    .setClusterName("envoy-control-xds")
                    )
            ).build()

    private fun createIngressFilter(group: Group): Filter {
        val ingressHttp = HttpConnectionManager.newBuilder()
                .setStatPrefix("ingress_http")
                .setUseRemoteAddress(boolValue(group.useRemoteAddress))
                .setXffNumTrustedHops(1)
                .setDelayedCloseTimeout(durationInSeconds(0))
                .setCodecType(HttpConnectionManager.CodecType.AUTO)
                .setRds(ingressRds(group.ads))
                .addHttpFilters(defaultEnvoyRouterHttpFilter)
                .setHttpProtocolOptions(ingressHttp1ProtocolOptions(group))

        if (group.accessLogEnabled) {
            ingressHttp.addAccessLog(accessLog(group))
        }

        return Filter.newBuilder()
                .setName("envoy.http_connection_manager")
                .setTypedConfig(ProtobufAny.pack(
                        ingressHttp.build()
                ))
                .build()
    }

    private fun ingressHttp1ProtocolOptions(group: Group): Http1ProtocolOptions? {
        return Http1ProtocolOptions.newBuilder()
                .setAcceptHttp10(true)
                .setDefaultHostForHttp10(group.serviceName)
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

    private fun accessLog(group: Group): AccessLog {
        return AccessLog.newBuilder()
                .setName("envoy.file_access_log")
                .setTypedConfig(
                        ProtobufAny.pack(
                                FileAccessLog.newBuilder()
                                        .setPath(group.accessLogPath)
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
