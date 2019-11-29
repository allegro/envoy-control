package pl.allegro.tech.servicemesh.envoycontrol.snapshot

import com.google.protobuf.*
import io.envoyproxy.envoy.api.v2.Listener
import io.envoyproxy.envoy.api.v2.core.*
import io.envoyproxy.envoy.api.v2.listener.Filter
import io.envoyproxy.envoy.api.v2.listener.FilterChain
import io.envoyproxy.envoy.config.accesslog.v2.FileAccessLog
import io.envoyproxy.envoy.config.filter.accesslog.v2.AccessLog
import io.envoyproxy.envoy.config.filter.http.lua.v2.Lua
import io.envoyproxy.envoy.config.filter.network.http_connection_manager.v2.HttpConnectionManager
import io.envoyproxy.envoy.config.filter.network.http_connection_manager.v2.HttpFilter
import io.envoyproxy.envoy.config.filter.network.http_connection_manager.v2.Rds
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group
import com.google.protobuf.Any as ProtobufAny
import io.envoyproxy.envoy.config.filter.http.header_to_metadata.v2.Config as HeaderToMetadataConfig

internal class EnvoyListenersFactory {
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
        return Filter.newBuilder()
                .setName("type.googleapis.com/envoy.config.filter.network.http_connection_manager.v2.HttpConnectionManager")
                .setTypedConfig(ProtobufAny.pack(
                        HttpConnectionManager.newBuilder()
                                .setStatPrefix("egress_http")
                                .setRds(defaultEgressRds)
                                .addHttpFilters(luaHttpFilter(group))
                                .addHttpFilters(defaultHeaderToMetadataFilter)
                                .addHttpFilters(defaultEnvoyRouterHttpFilter)
                                .setHttpProtocolOptions(egressHttp1ProtocolOptions())
                                .addAccessLog(if (group.accessLogEnabled) defaultAccessLog else emptyAccessLog)
                                .build()
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
                .setName("type.googleapis.com/envoy.config.filter.http.header_to_metadata.v2.Config")
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
                                ).build()
                ))
                .build()
    }

    private fun luaHttpFilter(group: Group): HttpFilter? {
        return HttpFilter.newBuilder()
                .setName("type.googleapis.com/envoy.config.filter.http.lua.v2.Lua")
                .setTypedConfig(ProtobufAny.pack(
                        Lua.newBuilder()
                                .setInlineCode("package.path = \"${group.luaScriptDir}/?.lua;\" .. package.path\n" +
                                        "local handler = require(\"handler\")\n" +
                                        "function envoy_on_request(request_handle)\n" +
                                        "  handler:envoy_on_request(request_handle)\n" +
                                        "end")
                                .build()
                ))
                .build()
    }

    private val defaultEgressRds = egressRds()

    private fun egressRds(): Rds? {
        return Rds.newBuilder()
                .setRouteConfigName("default_routes")
                .setConfigSource(
                        ConfigSource.newBuilder()
                                .setAds(AggregatedConfigSource.getDefaultInstance())
                                .setInitialFetchTimeout(durationInSeconds(20))
                                .build()
                )
                .build()
    }

    private fun createIngressFilter(group: Group): Filter {
        return Filter.newBuilder()
                .setName("type.googleapis.com/envoy.config.filter.network.http_connection_manager.v2.HttpConnectionManager")
                .setTypedConfig(ProtobufAny.pack(
                        HttpConnectionManager.newBuilder()
                                .setStatPrefix("ingress_http")
                                .setUseRemoteAddress(boolValue(group.useRemoteAddress))
                                .setXffNumTrustedHops(1)
                                .setDelayedCloseTimeout(durationInSeconds(0))
                                .addAccessLog(if (group.accessLogEnabled) defaultAccessLog else emptyAccessLog)
                                .setCodecType(HttpConnectionManager.CodecType.AUTO)
                                .setRds(defaultIngressRds)
                                .addHttpFilters(defaultEnvoyRouterHttpFilter)
                                .setHttpProtocolOptions(ingressHttp1ProtocolOptions(group))
                                .build()
                ))
                .build()
    }

    private fun ingressHttp1ProtocolOptions(group: Group): Http1ProtocolOptions? {
        return Http1ProtocolOptions.newBuilder()
                .setAcceptHttp10(true)
                .setDefaultHostForHttp10(group.serviceName)
                .build()
    }

    private val defaultIngressRds = ingressRds()

    private fun ingressRds(): Rds? {
        return Rds.newBuilder()
                .setRouteConfigName("ingress_secured_routes")
                .setConfigSource(ConfigSource.newBuilder()
                        .setAds(AggregatedConfigSource.getDefaultInstance())
                        .setInitialFetchTimeout(durationInSeconds(30))
                        .build()
                )
                .build()
    }

    private val defaultEnvoyRouterHttpFilter = envoyRouterHttpFilter()

    private fun envoyRouterHttpFilter() = HttpFilter.newBuilder().setName("type.googleapis.com/envoy.config.filter.http.router.v2.Router").build()

    private val defaultAccessLog = accessLog()

    private val emptyAccessLog = AccessLog.getDefaultInstance()

    private fun accessLog(): AccessLog? {
        return AccessLog.newBuilder()
                .setName("type.googleapis.com/envoy.config.accesslog.v2.FileAccessLog")
                .setTypedConfig(
                        ProtobufAny.pack(
                                FileAccessLog.newBuilder()
                                        .setJsonFormat(
                                                Struct.newBuilder()
                                                        .putFields("time", stringValue("%START_TIME(%FT%T.%3fZ)%"))
                                                        .putFields("message", stringValue("%PROTOCOL% %REQ(:METHOD)% %REQ(:authority)% %REQ(:PATH)% %DOWNSTREAM_REMOTE_ADDRESS% -> %UPSTREAM_HOST%"))
                                                        .putFields("level", stringValue("TRACE"))
                                                        .putFields("logger", stringValue("envoy.AccessLog"))
                                                        .putFields("access_log_type", stringValue("ingress"))
                                                        .putFields("request_protocol", stringValue("%PROTOCOL%"))
                                                        .putFields("request_method", stringValue("%REQ(:METHOD)%"))
                                                        .putFields("request_authority", stringValue("%REQ(:authority)%"))
                                                        .putFields("request_path", stringValue("%REQ(:PATH)%"))
                                                        .putFields("response_code", stringValue("%RESPONSE_CODE%"))
                                                        .putFields("response_flags", stringValue("%RESPONSE_FLAGS%"))
                                                        .putFields("bytes_received", stringValue("%BYTES_RECEIVED%"))
                                                        .putFields("bytes_sent", stringValue("%BYTES_SENT%"))
                                                        .putFields("duration_ms", stringValue("%DURATION%"))
                                                        .putFields("downstream_remote_address", stringValue("%DOWNSTREAM_REMOTE_ADDRESS%"))
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
