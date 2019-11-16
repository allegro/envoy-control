package pl.allegro.tech.servicemesh.envoycontrol.snapshot

import com.google.protobuf.Struct
import com.google.protobuf.UInt32Value
import com.google.protobuf.Value
import com.google.protobuf.util.Durations
import io.envoyproxy.controlplane.cache.Snapshot
import io.envoyproxy.envoy.api.v2.Cluster
import io.envoyproxy.envoy.api.v2.ClusterLoadAssignment
import io.envoyproxy.envoy.api.v2.auth.CertificateValidationContext
import io.envoyproxy.envoy.api.v2.auth.CommonTlsContext
import io.envoyproxy.envoy.api.v2.auth.UpstreamTlsContext
import io.envoyproxy.envoy.api.v2.cluster.CircuitBreakers
import io.envoyproxy.envoy.api.v2.cluster.OutlierDetection
import io.envoyproxy.envoy.api.v2.core.Address
import io.envoyproxy.envoy.api.v2.core.AggregatedConfigSource
import io.envoyproxy.envoy.api.v2.core.ApiConfigSource
import io.envoyproxy.envoy.api.v2.core.ConfigSource
import io.envoyproxy.envoy.api.v2.core.DataSource
import io.envoyproxy.envoy.api.v2.core.GrpcService
import io.envoyproxy.envoy.api.v2.core.Http2ProtocolOptions
import io.envoyproxy.envoy.api.v2.core.HttpProtocolOptions
import io.envoyproxy.envoy.api.v2.core.RoutingPriority
import io.envoyproxy.envoy.api.v2.core.SocketAddress
import io.envoyproxy.envoy.api.v2.endpoint.Endpoint
import io.envoyproxy.envoy.api.v2.endpoint.LbEndpoint
import io.envoyproxy.envoy.api.v2.endpoint.LocalityLbEndpoints
import pl.allegro.tech.servicemesh.envoycontrol.groups.AllServicesGroup
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group
import pl.allegro.tech.servicemesh.envoycontrol.groups.ServicesGroup

internal class EnvoyClustersFactory(
    private val properties: SnapshotProperties
) {
    private val httpProtocolOptions: HttpProtocolOptions = HttpProtocolOptions.newBuilder().setIdleTimeout(
            Durations.fromMillis(properties.egress.commonHttp.idleTimeout.toMillis())
    ).build()

    private val thresholds: List<CircuitBreakers.Thresholds> = mapPropertiesToThresholds()
    private val allThresholds = CircuitBreakers.newBuilder().addAllThresholds(thresholds).build()

    fun getClustersForServices(services: List<EnvoySnapshotFactory.ClusterConfiguration>, ads: Boolean): List<Cluster> {
        return services.map { edsCluster(it, ads) }
    }

    fun getClustersForGroup(group: Group, globalSnapshot: Snapshot): List<Cluster> =
        getEdsClustersForGroup(group, globalSnapshot) + getStrictDnsClustersForGroup(group)

    private fun getEdsClustersForGroup(group: Group, globalSnapshot: Snapshot): List<Cluster> {
        return when (group) {
            is ServicesGroup -> group.proxySettings.outgoing.getServiceDependencies()
                .mapNotNull { globalSnapshot.clusters().resources().get(it.service) }
            is AllServicesGroup -> globalSnapshot.clusters().resources().map { it.value }
        }
    }

    private fun getStrictDnsClustersForGroup(group: Group): List<Cluster> {
        return group.proxySettings.outgoing.getDomainDependencies().map {
            strictDnsCluster(it.getClusterName(), it.getHost(), it.getPort(), it.useSsl())
        }
    }

    private fun strictDnsCluster(clusterName: String, host: String, port: Int, ssl: Boolean): Cluster {
        var clusterBuilder = Cluster.newBuilder()

        if (properties.clusterOutlierDetection.enabled) {
            configureOutlierDetection(clusterBuilder)
        }

        clusterBuilder = clusterBuilder.setName(clusterName)
            .setType(Cluster.DiscoveryType.STRICT_DNS)
            .setConnectTimeout(Durations.fromMillis(properties.staticClusterConnectionTimeout.toMillis()))
            /*
                Default policy for resolving DNS names in Envoy resolves IPV6 addresses in first place
                (IPV4 addresses are ignored if IPV6 are available from domain).
                There is no policy in Envoy that works in reverse order - this is the reason we are forced to ignore
                IPV6 completely by setting policy that resolves only IPV4 addresses.
             */
            .setDnsLookupFamily(Cluster.DnsLookupFamily.V4_ONLY)
            .setLoadAssignment(
                ClusterLoadAssignment.newBuilder().setClusterName(clusterName).addEndpoints(
                    LocalityLbEndpoints.newBuilder().addLbEndpoints(
                        LbEndpoint.newBuilder().setEndpoint(
                            Endpoint.newBuilder().setAddress(
                                Address.newBuilder().setSocketAddress(
                                    SocketAddress.newBuilder().setAddress(host).setPortValue(port)
                                )
                            )
                        )
                    )
                )
            )
            .setLbPolicy(Cluster.LbPolicy.LEAST_REQUEST)

        if (ssl) {
            var tlsContextBuilder = UpstreamTlsContext.newBuilder()
            tlsContextBuilder = tlsContextBuilder.setCommonTlsContext(
                CommonTlsContext.newBuilder()
                    .setValidationContext(
                        CertificateValidationContext.newBuilder().setTrustedCa(
                            // TODO: https://github.com/allegro/envoy-control/issues/5
                            DataSource.newBuilder().setFilename(properties.trustedCaFile).build()
                        )
                    )
            )
            clusterBuilder = clusterBuilder.setTlsContext(tlsContextBuilder.build())
        }
        return clusterBuilder.build()
    }

    private fun edsCluster(clusterConfiguration: EnvoySnapshotFactory.ClusterConfiguration, ads: Boolean): Cluster {
        val clusterBuilder = Cluster.newBuilder()

        if (properties.clusterOutlierDetection.enabled) {
            configureOutlierDetection(clusterBuilder)
        }

        val cluster = clusterBuilder.setName(clusterConfiguration.serviceName)
            .setType(Cluster.DiscoveryType.EDS)
            .setConnectTimeout(Durations.fromMillis(properties.edsConnectionTimeout.toMillis()))
            .setEdsClusterConfig(
                Cluster.EdsClusterConfig.newBuilder().setEdsConfig(
                    if (ads) {
                        ConfigSource.newBuilder().setAds(AggregatedConfigSource.newBuilder())
                    } else {
                        ConfigSource.newBuilder().setApiConfigSource(
                            ApiConfigSource.newBuilder().setApiType(ApiConfigSource.ApiType.GRPC)
                                .addGrpcServices(0, GrpcService.newBuilder().setEnvoyGrpc(
                                    GrpcService.EnvoyGrpc.newBuilder()
                                        .setClusterName(properties.xdsClusterName)
                                )
                            )
                        )
                    }
                ).setServiceName(clusterConfiguration.serviceName)
            )
            .setLbPolicy(Cluster.LbPolicy.LEAST_REQUEST)
            .configureLbSubsets()

        cluster.setCommonHttpProtocolOptions(httpProtocolOptions)
        cluster.setCircuitBreakers(allThresholds)

        if (clusterConfiguration.http2Enabled) {
            cluster.setHttp2ProtocolOptions(Http2ProtocolOptions.getDefaultInstance())
        }

        return cluster.build()
    }

    private fun Cluster.Builder.configureLbSubsets(): Cluster.Builder {
        val canaryEnabled = properties.loadBalancing.canary.enabled
        val tagsEnabled = properties.routing.serviceTags.enabled

        if (!canaryEnabled && !tagsEnabled) {
            return this
        }

        val defaultSubset = Struct.newBuilder()
        if (canaryEnabled) {
            defaultSubset.putFields(
                properties.loadBalancing.regularMetadataKey,
                Value.newBuilder().setBoolValue(true).build()
            )
        }

        return setLbSubsetConfig(Cluster.LbSubsetConfig.newBuilder()
            .setFallbackPolicy(Cluster.LbSubsetConfig.LbSubsetFallbackPolicy.DEFAULT_SUBSET)
            .setDefaultSubset(defaultSubset)
            .apply {
                if (canaryEnabled) {
                    addSubsetSelectors(Cluster.LbSubsetConfig.LbSubsetSelector.newBuilder()
                        .addKeys(properties.loadBalancing.canary.metadataKey)
                    )
                }
                if (tagsEnabled) {
                    addSubsetSelectors(Cluster.LbSubsetConfig.LbSubsetSelector.newBuilder()
                        .addKeys(properties.routing.serviceTags.metadataKey)
                        .setFallbackPolicy(
                            Cluster.LbSubsetConfig.LbSubsetSelector.LbSubsetSelectorFallbackPolicy.NO_FALLBACK)
                    )
                    setListAsAny(true) // allowing for an endpoint to have multiple tags
                }
                if (tagsEnabled && canaryEnabled) {
                    addSubsetSelectors(Cluster.LbSubsetConfig.LbSubsetSelector.newBuilder()
                        .addKeys(properties.routing.serviceTags.metadataKey)
                        .addKeys(properties.loadBalancing.canary.metadataKey)
                        .setFallbackPolicy(
                            Cluster.LbSubsetConfig.LbSubsetSelector.LbSubsetSelectorFallbackPolicy.KEYS_SUBSET)
                        .addFallbackKeysSubset(properties.routing.serviceTags.metadataKey)
                    )
                }
            }
        )
    }

    private fun mapPropertiesToThresholds(): List<CircuitBreakers.Thresholds> {
        return listOf(
            convertThreshold(properties.egress.commonHttp.circuitBreakers.defaultThreshold),
            convertThreshold(properties.egress.commonHttp.circuitBreakers.highThreshold)
        )
    }

    private fun convertThreshold(threshold: Threshold): CircuitBreakers.Thresholds {
        val thresholdsBuilder = CircuitBreakers.Thresholds.newBuilder()
        thresholdsBuilder.maxConnections = UInt32Value.of(threshold.maxConnections)
        thresholdsBuilder.maxPendingRequests = UInt32Value.of(threshold.maxPendingRequests)
        thresholdsBuilder.maxRequests = UInt32Value.of(threshold.maxRequests)
        thresholdsBuilder.maxRetries = UInt32Value.of(threshold.maxRetries)
        when (threshold.priority.toUpperCase()) {
            "DEFAULT" -> thresholdsBuilder.priority = RoutingPriority.DEFAULT
            "HIGH" -> thresholdsBuilder.priority = RoutingPriority.HIGH
            else -> thresholdsBuilder.priority = RoutingPriority.UNRECOGNIZED
        }
        return thresholdsBuilder.build()
    }

    private fun configureOutlierDetection(clusterBuilder: Cluster.Builder) {
        clusterBuilder
            .setOutlierDetection(
                OutlierDetection.newBuilder()
                    .setConsecutive5Xx(UInt32Value.of(properties.clusterOutlierDetection.consecutive5xx))
                    .setInterval(Durations.fromMillis(properties.clusterOutlierDetection.interval.toMillis()))
                    .setMaxEjectionPercent(UInt32Value.of(properties.clusterOutlierDetection.maxEjectionPercent))
                    .setEnforcingSuccessRate(UInt32Value.of(properties.clusterOutlierDetection.enforcingSuccessRate))
                    .setBaseEjectionTime(Durations.fromMillis(
                        properties.clusterOutlierDetection.baseEjectionTime.toMillis())
                    )
                    .setEnforcingConsecutive5Xx(
                        UInt32Value.of(properties.clusterOutlierDetection.enforcingConsecutive5xx)
                    )
                    .setSuccessRateMinimumHosts(
                        UInt32Value.of(properties.clusterOutlierDetection.successRateMinimumHosts)
                    )
                    .setSuccessRateRequestVolume(
                        UInt32Value.of(properties.clusterOutlierDetection.successRateRequestVolume)
                    )
                    .setSuccessRateStdevFactor(
                        UInt32Value.of(properties.clusterOutlierDetection.successRateStdevFactor)
                    )
                    .setConsecutiveGatewayFailure(
                        UInt32Value.of(properties.clusterOutlierDetection.consecutiveGatewayFailure)
                    )
                    .setEnforcingConsecutiveGatewayFailure(
                        UInt32Value.of(properties.clusterOutlierDetection.enforcingConsecutiveGatewayFailure)
                    )
            )
    }
}
