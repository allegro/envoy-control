package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters

import com.google.protobuf.BoolValue
import com.google.protobuf.Duration
import com.google.protobuf.util.Durations
import io.envoyproxy.envoy.config.core.v3.AggregatedConfigSource
import io.envoyproxy.envoy.config.core.v3.ApiVersion
import io.envoyproxy.envoy.config.core.v3.ConfigSource
import io.envoyproxy.envoy.config.core.v3.Http1ProtocolOptions
import io.envoyproxy.envoy.config.core.v3.HttpProtocolOptions
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.Rds
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.GlobalSnapshot
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.HttpFilterFactory
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.config.LocalReplyConfigFactory

class HttpConnectionManagerFactory(
    val snapshotProperties: SnapshotProperties
) {

    enum class Direction {
        INGRESS,
        EGRESS
    }

    private val localReplyConfig = LocalReplyConfigFactory(
        snapshotProperties.dynamicListeners.localReplyMapper
    ).configuration

    private val dynamicForwardProxyFilter = DynamicForwardProxyFilter(
        snapshotProperties.dynamicForwardProxy
    ).filter

    private val accessLogFilter = AccessLogFilter(snapshotProperties)

    @SuppressWarnings("LongParameterList")
    fun createFilter(
        group: Group,
        globalSnapshot: GlobalSnapshot,
        filters: List<HttpFilterFactory>,
        routeConfigName: String,
        statPrefix: String,
        initialFetchTimeout: Duration,
        direction: Direction
    ): HttpConnectionManager? {
        val listenersConfig = group.listenersConfig!!

        val connectionManagerBuilder = HttpConnectionManager.newBuilder()
            .setStatPrefix(statPrefix)
            .setRds(setupRds(initialFetchTimeout, routeConfigName))
            .setGenerateRequestId(BoolValue.newBuilder().setValue(listenersConfig.generateRequestId).build())
            .setPreserveExternalRequestId(listenersConfig.preserveExternalRequestId)

        when (direction) {
            Direction.INGRESS -> {
                val connectionIdleTimeout = group.proxySettings.incoming.timeoutPolicy.connectionIdleTimeout
                    ?: Durations.fromMillis(snapshotProperties.localService.connectionIdleTimeout.toMillis())
                val httpProtocolOptions = HttpProtocolOptions.newBuilder().setIdleTimeout(connectionIdleTimeout).build()
                connectionManagerBuilder
                    .setUseRemoteAddress(BoolValue.newBuilder().setValue(listenersConfig.useRemoteAddress).build())
                    .setDelayedCloseTimeout(Duration.newBuilder().setSeconds(0).build())
                    .setCommonHttpProtocolOptions(httpProtocolOptions)
                    .setCodecType(HttpConnectionManager.CodecType.AUTO)
                    .setHttpProtocolOptions(ingressHttp1ProtocolOptions(group.serviceName))
                if (listenersConfig.useRemoteAddress) {
                    connectionManagerBuilder.setXffNumTrustedHops(
                        snapshotProperties.dynamicListeners.httpFilters.ingressXffNumTrustedHops
                    )
                }
            }
            Direction.EGRESS -> {
                connectionManagerBuilder
                    .setHttpProtocolOptions(
                        Http1ProtocolOptions.newBuilder()
                            .setAllowAbsoluteUrl(BoolValue.newBuilder().setValue(true).build())
                            .build()
                    )
                if (group.proxySettings.outgoing.getDomainPatternDependencies().isNotEmpty()) {
                    connectionManagerBuilder.addHttpFilters(dynamicForwardProxyFilter)
                }
            }
        }

        if (snapshotProperties.dynamicListeners.localReplyMapper.enabled) {
            connectionManagerBuilder.localReplyConfig = localReplyConfig
        }

        if (listenersConfig.accessLogEnabled) {
            connectionManagerBuilder.addAccessLog(
                accessLogFilter.createFilter(
                    listenersConfig.accessLogPath,
                    direction.name.lowercase(),
                    listenersConfig.accessLogFilterSettings
                )
            )
        }
        addHttpFilters(connectionManagerBuilder, filters, group, globalSnapshot)

        return connectionManagerBuilder.build()
    }

    private fun setupRds(
        rdsInitialFetchTimeout: Duration,
        routeConfigName: String
    ): Rds = Rds.newBuilder()
            .setRouteConfigName(routeConfigName)
            .setConfigSource(
                ConfigSource.newBuilder()
                    .setInitialFetchTimeout(rdsInitialFetchTimeout)
                    .setResourceApiVersion(ApiVersion.V3)
                    .setAds(AggregatedConfigSource.getDefaultInstance())
                    .build()
            )
            .build()

    private fun ingressHttp1ProtocolOptions(serviceName: String): Http1ProtocolOptions? {
        return Http1ProtocolOptions.newBuilder()
            .setAcceptHttp10(true)
            .setDefaultHostForHttp10(serviceName)
            .build()
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
}
