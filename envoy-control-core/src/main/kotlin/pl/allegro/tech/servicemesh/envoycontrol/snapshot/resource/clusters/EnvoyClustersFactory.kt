package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.clusters

import com.google.protobuf.Any
import com.google.protobuf.Struct
import com.google.protobuf.UInt32Value
import com.google.protobuf.Value
import com.google.protobuf.util.Durations
import io.envoyproxy.envoy.config.cluster.v3.CircuitBreakers
import io.envoyproxy.envoy.config.cluster.v3.Cluster
import io.envoyproxy.envoy.config.cluster.v3.OutlierDetection
import io.envoyproxy.envoy.config.core.v3.Address
import io.envoyproxy.envoy.config.core.v3.AggregatedConfigSource
import io.envoyproxy.envoy.config.core.v3.ApiConfigSource
import io.envoyproxy.envoy.config.core.v3.ConfigSource
import io.envoyproxy.envoy.config.core.v3.DataSource
import io.envoyproxy.envoy.config.core.v3.GrpcService
import io.envoyproxy.envoy.config.core.v3.Http2ProtocolOptions
import io.envoyproxy.envoy.config.core.v3.HttpProtocolOptions
import io.envoyproxy.envoy.config.core.v3.RoutingPriority
import io.envoyproxy.envoy.config.core.v3.SocketAddress
import io.envoyproxy.envoy.config.core.v3.TransportSocket
import io.envoyproxy.envoy.config.core.v3.UpstreamHttpProtocolOptions
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment
import io.envoyproxy.envoy.config.endpoint.v3.Endpoint
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint
import io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CertificateValidationContext
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CommonTlsContext
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.SdsSecretConfig
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.TlsParameters
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.UpstreamTlsContext
import io.envoyproxy.envoy.type.matcher.v3.StringMatcher
import pl.allegro.tech.servicemesh.envoycontrol.groups.AllServicesGroup
import pl.allegro.tech.servicemesh.envoycontrol.groups.CommunicationMode
import pl.allegro.tech.servicemesh.envoycontrol.groups.CommunicationMode.ADS
import pl.allegro.tech.servicemesh.envoycontrol.groups.CommunicationMode.XDS
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group
import pl.allegro.tech.servicemesh.envoycontrol.groups.ServicesGroup
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.ClusterConfiguration
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.GlobalSnapshot
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.Threshold
import pl.allegro.tech.servicemesh.envoycontrol.protocol.TlsUtils

class EnvoyClustersFactory(
    private val properties: SnapshotProperties
) {
    private val httpProtocolOptions: HttpProtocolOptions = HttpProtocolOptions.newBuilder().setIdleTimeout(
            Durations.fromMillis(properties.egress.commonHttp.idleTimeout.toMillis())
    ).build()

    private val thresholds: List<CircuitBreakers.Thresholds> = mapPropertiesToThresholds()
    private val allThresholds = CircuitBreakers.newBuilder().addAllThresholds(thresholds).build()
    private val tlsProperties = properties.incomingPermissions.tlsAuthentication
    private val matchPlaintextContext = Cluster.TransportSocketMatch.newBuilder()
            .setName("plaintext_match")
            .setTransportSocket(
                    TransportSocket.newBuilder().setName("envoy.transport_sockets.raw_buffer").build()
            )
            .build()

    private val tlsContextMatch = Struct.newBuilder()
            .putFields(tlsProperties.tlsContextMetadataMatchKey, Value.newBuilder().setBoolValue(true).build())
            .build()

    fun getClustersForServices(
        services: Collection<ClusterConfiguration>,
        communicationMode: CommunicationMode
    ): List<Cluster> {
        return services.map { edsCluster(it, communicationMode) }
    }

    fun getSecuredClusters(insecureClusters: List<Cluster>): List<Cluster> {
        return insecureClusters.map { cluster ->
            val upstreamTlsContext = createTlsContextWithSdsSecretConfig(cluster.name)
            val secureCluster = Cluster.newBuilder(cluster)

            val matchTlsContext = Cluster.TransportSocketMatch.newBuilder()
                    .setName("mtls_match")
                    .setMatch(tlsContextMatch)
                    .setTransportSocket(TransportSocket.newBuilder()
                            .setName("envoy.transport_sockets.tls")
                            .setTypedConfig(Any.pack(upstreamTlsContext)))
                    .build()

            secureCluster.addAllTransportSocketMatches(listOf(matchTlsContext, matchPlaintextContext))
                    .build()
        }
    }

    fun getClustersForGroup(group: Group, globalSnapshot: GlobalSnapshot): List<Cluster> =
        getEdsClustersForGroup(group, globalSnapshot) + getStrictDnsClustersForGroup(group)

    private fun getEdsClustersForGroup(group: Group, globalSnapshot: GlobalSnapshot): List<Cluster> {
        val clusters = if (enableTlsForGroup(group)) {
            globalSnapshot.securedClusters.resources()
        } else {
            globalSnapshot.clusters.resources()
        }

        return when (group) {
            is ServicesGroup -> group.proxySettings.outgoing.getServiceDependencies().mapNotNull {
                clusters.get(it.service)
            }
            is AllServicesGroup -> globalSnapshot.allServicesNames.mapNotNull {
                clusters.get(it)
            }
        }
    }

    private fun enableTlsForGroup(group: Group): Boolean {
        return group.listenersConfig?.hasStaticSecretsDefined ?: false
    }

    private val commonTlsParams = TlsParameters.newBuilder()
            .setTlsMinimumProtocolVersion(tlsProperties.protocol.minimumVersion)
            .setTlsMaximumProtocolVersion(tlsProperties.protocol.maximumVersion)
            .addAllCipherSuites(tlsProperties.protocol.cipherSuites)
            .build()

    private val validationContextSecretConfig = SdsSecretConfig.newBuilder()
            .setName(tlsProperties.validationContextSecretName).build()

    private val tlsCertificateSecretConfig = SdsSecretConfig.newBuilder()
            .setName(tlsProperties.tlsCertificateSecretName).build()

    private fun createTlsContextWithSdsSecretConfig(serviceName: String): UpstreamTlsContext {
        val sanMatch = TlsUtils.resolveSanUri(serviceName, tlsProperties.sanUriFormat)
        return UpstreamTlsContext.newBuilder()
                .setCommonTlsContext(CommonTlsContext.newBuilder()
                        .setTlsParams(commonTlsParams)
                        .setCombinedValidationContext(CommonTlsContext.CombinedCertificateValidationContext.newBuilder()
                                .setDefaultValidationContext(CertificateValidationContext.newBuilder()
                                        .addAllMatchSubjectAltNames(listOf(StringMatcher.newBuilder()
                                                .setExact(sanMatch)
                                                .build()
                                        )).build())
                                .setValidationContextSdsSecretConfig(validationContextSecretConfig)
                                .build()
                        )
                        .addTlsCertificateSdsSecretConfigs(tlsCertificateSecretConfig)
                        .build()
                )
                .build()
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
            .setLbPolicy(properties.loadBalancing.policy)

        if (ssl) {
            val commonTlsContext = CommonTlsContext.newBuilder()
                    .setValidationContext(
                            CertificateValidationContext.newBuilder()
                                    .setTrustedCa(
                                            // TODO: https://github.com/allegro/envoy-control/issues/5
                                            DataSource.newBuilder().setFilename(properties.trustedCaFile).build()
                                    ).build()
                    ).build()

            val upstreamTlsContext = UpstreamTlsContext.newBuilder().setCommonTlsContext(commonTlsContext)
                // for envoy >= 1.14.0-dev it will be overridden by setAutoSni below
                // TODO(https://github.com/allegro/envoy-control/issues/97)
                //     remove when envoy < 1.14.0-dev will be not supported
                .setSni(host)
                .build()
            val transportSocket = TransportSocket.newBuilder()
                    .setTypedConfig(Any.pack(
                            upstreamTlsContext
                    ))
                    .setName("envoy.transport_sockets.tls").build()

            clusterBuilder
                    .setTransportSocket(transportSocket)
                    .setUpstreamHttpProtocolOptions(
                            UpstreamHttpProtocolOptions.newBuilder().setAutoSanValidation(true).setAutoSni(true).build()
                    )
        }

        return clusterBuilder.build()
    }

    private fun edsCluster(
        clusterConfiguration: ClusterConfiguration,
        communicationMode: CommunicationMode
    ): Cluster {
        val clusterBuilder = Cluster.newBuilder()

        if (properties.clusterOutlierDetection.enabled) {
            configureOutlierDetection(clusterBuilder)
        }

        // cluster name must be equal to service name because it is used in other places
        val cluster = clusterBuilder.setName(clusterConfiguration.serviceName)
            .setType(Cluster.DiscoveryType.EDS)
            .setConnectTimeout(Durations.fromMillis(properties.edsConnectionTimeout.toMillis()))
            .setEdsClusterConfig(
                Cluster.EdsClusterConfig.newBuilder().setEdsConfig(
                    when (communicationMode) {
                        ADS -> ConfigSource.newBuilder().setAds(AggregatedConfigSource.newBuilder())
                        XDS ->
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
            .setLbPolicy(properties.loadBalancing.policy)
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
                    addTagsAndCanarySelector()
                }
            }
        )
    }

    private fun Cluster.LbSubsetConfig.Builder.addTagsAndCanarySelector() = this.addSubsetSelectors(
        Cluster.LbSubsetConfig.LbSubsetSelector.newBuilder()
            .addKeys(properties.routing.serviceTags.metadataKey)
            .addKeys(properties.loadBalancing.canary.metadataKey)
            .run {
                if (properties.loadBalancing.useKeysSubsetFallbackPolicy) {
                    setFallbackPolicy(
                        Cluster.LbSubsetConfig.LbSubsetSelector.LbSubsetSelectorFallbackPolicy.KEYS_SUBSET)
                    addFallbackKeysSubset(properties.routing.serviceTags.metadataKey)
                } else {
                    // optionally don't use KEYS_SUBSET for compatibility with envoy version <= 1.12.x
                    setFallbackPolicy(
                        Cluster.LbSubsetConfig.LbSubsetSelector.LbSubsetSelectorFallbackPolicy.NO_FALLBACK)
                }
            }
    )

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
