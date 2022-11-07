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
import io.envoyproxy.envoy.config.core.v3.ApiVersion
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
import io.envoyproxy.envoy.extensions.clusters.dynamic_forward_proxy.v3.ClusterConfig
import io.envoyproxy.envoy.extensions.common.dynamic_forward_proxy.v3.DnsCacheConfig
import io.envoyproxy.envoy.extensions.common.tap.v3.AdminConfig
import io.envoyproxy.envoy.extensions.common.tap.v3.CommonExtensionConfig
import io.envoyproxy.envoy.extensions.transport_sockets.tap.v3.Tap
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CertificateValidationContext
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CommonTlsContext
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.SdsSecretConfig
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.TlsParameters
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.UpstreamTlsContext
import pl.allegro.tech.servicemesh.envoycontrol.groups.AllServicesGroup
import pl.allegro.tech.servicemesh.envoycontrol.groups.AllServicesIngressGatewayGroup
import pl.allegro.tech.servicemesh.envoycontrol.groups.CommunicationMode
import pl.allegro.tech.servicemesh.envoycontrol.groups.CommunicationMode.ADS
import pl.allegro.tech.servicemesh.envoycontrol.groups.CommunicationMode.XDS
import pl.allegro.tech.servicemesh.envoycontrol.groups.DependencySettings
import pl.allegro.tech.servicemesh.envoycontrol.groups.DomainDependency
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group
import pl.allegro.tech.servicemesh.envoycontrol.groups.IngressGatewayGroup
import pl.allegro.tech.servicemesh.envoycontrol.groups.ServicesIngressGatewayGroup
import pl.allegro.tech.servicemesh.envoycontrol.groups.ServicesGroup
import pl.allegro.tech.servicemesh.envoycontrol.groups.containsGlobalRateLimits
import pl.allegro.tech.servicemesh.envoycontrol.logger
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.ClusterConfiguration
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.GlobalSnapshot
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.OAuthProvider
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.Threshold
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters.SanUriMatcherFactory

class EnvoyClustersFactory(
    private val properties: SnapshotProperties
) {
    private val httpProtocolOptions: HttpProtocolOptions = HttpProtocolOptions.newBuilder().setIdleTimeout(
        Durations.fromMillis(properties.egress.commonHttp.connectionIdleTimeout.toMillis())
    ).build()

    private val dynamicForwardProxyCluster: Cluster = createDynamicForwardProxyCluster()
    private val thresholds: List<CircuitBreakers.Thresholds> = mapPropertiesToThresholds()
    private val allThresholds = CircuitBreakers.newBuilder().addAllThresholds(thresholds).build()
    private val tlsProperties = properties.incomingPermissions.tlsAuthentication
    private val sanUriMatcher = SanUriMatcherFactory(tlsProperties)

    private val tlsContextMatch = Struct.newBuilder()
        .putFields(tlsProperties.tlsContextMetadataMatchKey, Value.newBuilder().setBoolValue(true).build())
        .build()

    private val clustersForJWT: List<Cluster> =
        properties.jwt.providers.values.mapNotNull(this::clusterForOAuthProvider)

    companion object {
        private val logger by logger()
    }

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
                .setTransportSocket(wrapTransportSocket(cluster.name) {
                    TransportSocket.newBuilder()
                        .setName("envoy.transport_sockets.tls")
                        .setTypedConfig(Any.pack(upstreamTlsContext))
                        .build()
                })
                .build()

            secureCluster.addAllTransportSocketMatches(
                listOf(
                    matchTlsContext,
                    createMatchPlainText("plaintext_${cluster.name}")
                )
            )
                .build()
        }
    }

    fun getClustersForGroup(group: Group, globalSnapshot: GlobalSnapshot): List<Cluster> =
        getEdsClustersForGroup(group, globalSnapshot) + getStrictDnsClustersForGroup(group) + clustersForJWT +
            getRateLimitClusterForGroup(group, globalSnapshot)

    private fun clusterForOAuthProvider(provider: OAuthProvider): Cluster? {
        if (provider.createCluster) {
            val cluster = Cluster.newBuilder()
                .setName(provider.clusterName)
                .setType(Cluster.DiscoveryType.STRICT_DNS)
                .setConnectTimeout(Durations.fromMillis(provider.connectionTimeout.toMillis()))
                .setLoadAssignment(
                    ClusterLoadAssignment.newBuilder()
                        .setClusterName(provider.clusterName)
                        .addEndpoints(
                            LocalityLbEndpoints.newBuilder().addLbEndpoints(
                                LbEndpoint.newBuilder().setEndpoint(
                                    Endpoint.newBuilder().setAddress(
                                        Address.newBuilder().setSocketAddress(
                                            SocketAddress.newBuilder()
                                                .setAddress(provider.jwksUri.host)
                                                .setPortValue(provider.clusterPort)
                                        )
                                    )
                                )
                            )
                        )
                )

            if (provider.jwksUri.scheme == "https") {
                cluster.transportSocket = wrapTransportSocket(cluster.name) {
                    TransportSocket.newBuilder()
                        .setName("envoy.transport_sockets.tls")
                        .setTypedConfig(
                            Any.pack(
                                UpstreamTlsContext.newBuilder().setSni(provider.jwksUri.host)
                                    .setCommonTlsContext(
                                        CommonTlsContext.newBuilder()
                                            .setValidationContext(
                                                CertificateValidationContext.newBuilder()
                                                    .setTrustedCa(
                                                        DataSource.newBuilder().setFilename(properties.trustedCaFile)
                                                    )
                                            )
                                    ).build()
                            )
                        ).build()
                }
            } else {
                logger.warn("Jwks url [${provider.jwksUri}] is not using HTTPS scheme.")
            }
            return cluster.build()
        } else {
            return null
        }
    }

    private fun getRateLimitClusterForGroup(group: Group, globalSnapshot: GlobalSnapshot): List<Cluster> {
        if (group.proxySettings.incoming.rateLimitEndpoints.containsGlobalRateLimits()) {
            val cluster = globalSnapshot.clusters[properties.rateLimit.serviceName]

            if (cluster != null) {
                return listOf(Cluster.newBuilder(cluster).build())
            }

            logger.warn(
                "ratelimit service [{}] cluster required for service [{}] has not been found.",
                properties.rateLimit.serviceName,
                group.serviceName
            )
        }

        return emptyList()
    }

    private fun getEdsClustersForGroup(group: Group, globalSnapshot: GlobalSnapshot): List<Cluster> {
        val clusters: Map<String, Cluster> = if (enableTlsForGroup(group) && group !is IngressGatewayGroup) {
            globalSnapshot.securedClusters
        } else {
            globalSnapshot.clusters
        }

        val serviceDependencies = group.proxySettings.outgoing.getServiceDependencies().associateBy { it.service }

        val clustersForGroup = when (group) {
            is ServicesGroup, is ServicesIngressGatewayGroup -> serviceDependencies.mapNotNull {
                createClusterForGroup(it.value.settings, clusters[it.key])
            }
            is AllServicesGroup, is AllServicesIngressGatewayGroup -> {
                globalSnapshot.allServicesNames.mapNotNull {
                    val dependency = serviceDependencies[it]
                    if (dependency != null && dependency.settings.timeoutPolicy.connectionIdleTimeout != null) {
                        createClusterForGroup(dependency.settings, clusters[it])
                    } else {
                        createClusterForGroup(group.proxySettings.outgoing.defaultServiceSettings, clusters[it])
                    }
                }
            }
        }

        if (shouldAddDynamicForwardProxyCluster(group)) {
            return listOf(dynamicForwardProxyCluster) + clustersForGroup
        }
        return clustersForGroup
    }

    private fun createClusterForGroup(dependencySettings: DependencySettings, cluster: Cluster?): Cluster? {
        return cluster?.let {
            val idleTimeoutPolicy =
                dependencySettings.timeoutPolicy.connectionIdleTimeout ?: cluster.commonHttpProtocolOptions.idleTimeout
            Cluster.newBuilder(cluster)
                .setCommonHttpProtocolOptions(
                    HttpProtocolOptions.newBuilder().setIdleTimeout(idleTimeoutPolicy)
                ).build()
        }
    }

    private fun shouldAddDynamicForwardProxyCluster(group: Group) =
        group.proxySettings.outgoing.getDomainPatternDependencies().isNotEmpty()

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
        .setName(tlsProperties.tlsCertificateSecretName)
        .build()

    private fun createTlsContextWithSdsSecretConfig(serviceName: String): UpstreamTlsContext {
        val sanMatch = sanUriMatcher.createSanUriMatcher(serviceName)
        val gatewaySanMatch = sanUriMatcher.createSanUriMatcher(properties.dcIngressGatewayService)
        return UpstreamTlsContext.newBuilder()
            .setCommonTlsContext(
                CommonTlsContext.newBuilder()
                    .setTlsParams(commonTlsParams)
                    .setCombinedValidationContext(
                        CommonTlsContext.CombinedCertificateValidationContext.newBuilder()
                            .setDefaultValidationContext(
                                CertificateValidationContext.newBuilder()
                                    .addAllMatchSubjectAltNames(listOf(sanMatch, gatewaySanMatch))
                                    .build()
                            )
                            .setValidationContextSdsSecretConfig(validationContextSecretConfig)
                            .build()
                    )
                    .addTlsCertificateSdsSecretConfigs(tlsCertificateSecretConfig)
                    .build()
            )
            .build()
    }

    private fun getStrictDnsClustersForGroup(group: Group): List<Cluster> {
        val useTransparentProxy = group.listenersConfig?.useTransparentProxy ?: false
        return group.proxySettings.outgoing.getDomainDependencies().map {
            strictDnsCluster(
                it,
                useTransparentProxy
            )
        }
    }

    private fun strictDnsCluster(
        domainDependency: DomainDependency,
        useTransparentProxy: Boolean
    ): Cluster {
        var clusterBuilder = Cluster.newBuilder()

        if (properties.clusterOutlierDetection.enabled) {
            configureOutlierDetection(clusterBuilder)
        }

        clusterBuilder = clusterBuilder.setName(domainDependency.getClusterName())
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
                ClusterLoadAssignment.newBuilder().setClusterName(domainDependency.getClusterName()).addEndpoints(
                    LocalityLbEndpoints.newBuilder().addLbEndpoints(
                        LbEndpoint.newBuilder().setEndpoint(
                            Endpoint.newBuilder().setAddress(
                                Address.newBuilder().setSocketAddress(
                                    SocketAddress.newBuilder().setAddress(domainDependency.getHost())
                                        .setPortValue(domainDependency.getPort())
                                )
                            )
                        )
                    )
                )
            )
            .setLbPolicy(properties.loadBalancing.policy)

        if (shouldAttachCertificateToCluster(domainDependency.useSsl(), useTransparentProxy)) {

            val commonTlsContext = CommonTlsContext.newBuilder()
                .setValidationContext(
                    CertificateValidationContext.newBuilder()
                        .setTrustedCa(
                            // TODO: https://github.com/allegro/envoy-control/issues/5
                            DataSource.newBuilder().setFilename(properties.trustedCaFile).build()
                        ).build()
                ).build()

            val upstreamTlsContext = UpstreamTlsContext.newBuilder().setCommonTlsContext(commonTlsContext).build()
            val transportSocket = wrapTransportSocket(domainDependency.getClusterName()) {
                TransportSocket.newBuilder()
                    .setTypedConfig(
                        Any.pack(
                            upstreamTlsContext
                        )
                    )
                    .setName("envoy.transport_sockets.tls").build()
            }

            clusterBuilder
                .setTransportSocket(transportSocket)
                .setUpstreamHttpProtocolOptions(
                    UpstreamHttpProtocolOptions.newBuilder()
                        .setAutoSanValidation(true)
                        .setAutoSni(true)
                        .build()
                )
        }
        domainDependency.settings.timeoutPolicy.connectionIdleTimeout?.let {
            clusterBuilder.setCommonHttpProtocolOptions(HttpProtocolOptions.newBuilder().setIdleTimeout(it))
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
                        // here we do not have group information
                        ADS -> ConfigSource.newBuilder()
                            .setResourceApiVersion(ApiVersion.V3)
                            .setAds(AggregatedConfigSource.newBuilder())
                        XDS ->
                            ConfigSource.newBuilder()
                                .setResourceApiVersion(ApiVersion.V3)
                                .setApiConfigSource(
                                    ApiConfigSource.newBuilder()
                                        .setApiType(
                                            if (properties.deltaXdsEnabled) {
                                                ApiConfigSource.ApiType.DELTA_GRPC
                                            } else {
                                                ApiConfigSource.ApiType.GRPC
                                            }
                                        )
                                        .setTransportApiVersion(ApiVersion.V3)
                                        .addGrpcServices(
                                            0, GrpcService.newBuilder().setEnvoyGrpc(
                                                GrpcService.EnvoyGrpc.newBuilder()
                                                    .setClusterName(properties.xdsClusterName)
                                            )
                                        )
                                )
                    }
                ).setServiceName(clusterConfiguration.serviceName)
            )
            .setLbPolicy(properties.loadBalancing.policy)
            // TODO: if we want to have multiple memory-backend instances of ratelimit
            // then we should have consistency hashed lb
            // setting RING_HASH here is not enough (but it's probably required so I leave it here)
            // .setLbPolicy(
            //     when (properties.rateLimit.serviceName == clusterConfiguration.serviceName) {
            //         true -> Cluster.LbPolicy.RING_HASH
            //         else -> properties.loadBalancing.policy
            //     }
            // )
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
                    addSubsetSelectors(
                        Cluster.LbSubsetConfig.LbSubsetSelector.newBuilder()
                            .addKeys(properties.loadBalancing.canary.metadataKey)
                    )
                }
                if (tagsEnabled) {
                    addSubsetSelectors(
                        Cluster.LbSubsetConfig.LbSubsetSelector.newBuilder()
                            .addKeys(properties.routing.serviceTags.metadataKey)
                            .setFallbackPolicy(
                                Cluster.LbSubsetConfig.LbSubsetSelector.LbSubsetSelectorFallbackPolicy.NO_FALLBACK
                            )
                    )
                    setListAsAny(true) // allowing for an endpoint to have multiple tags
                }
                if (tagsEnabled && canaryEnabled) {
                    addTagsAndCanarySelector()
                }
            }
        )
    }

    private fun shouldAttachCertificateToCluster(ssl: Boolean, useTransparentProxy: Boolean) =
        ssl && !useTransparentProxy

    private fun Cluster.LbSubsetConfig.Builder.addTagsAndCanarySelector() = this.addSubsetSelectors(
        Cluster.LbSubsetConfig.LbSubsetSelector.newBuilder()
            .addKeys(properties.routing.serviceTags.metadataKey)
            .addKeys(properties.loadBalancing.canary.metadataKey)
            .run {
                if (properties.loadBalancing.useKeysSubsetFallbackPolicy) {
                    setFallbackPolicy(
                        Cluster.LbSubsetConfig.LbSubsetSelector.LbSubsetSelectorFallbackPolicy.KEYS_SUBSET
                    )
                    addFallbackKeysSubset(properties.routing.serviceTags.metadataKey)
                } else {
                    // optionally don't use KEYS_SUBSET for compatibility with envoy version <= 1.12.x
                    setFallbackPolicy(
                        Cluster.LbSubsetConfig.LbSubsetSelector.LbSubsetSelectorFallbackPolicy.NO_FALLBACK
                    )
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
                    .setBaseEjectionTime(
                        Durations.fromMillis(
                            properties.clusterOutlierDetection.baseEjectionTime.toMillis()
                        )
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

    private fun createDynamicForwardProxyCluster(): Cluster {
        return Cluster.newBuilder()
            .setName(properties.dynamicForwardProxy.clusterName)
            .setConnectTimeout(Durations.fromMillis(properties.dynamicForwardProxy.connectionTimeout.toMillis()))
            .setLbPolicy(Cluster.LbPolicy.CLUSTER_PROVIDED)
            .setClusterType(
                Cluster.CustomClusterType.newBuilder()
                    .setName("envoy.clusters.dynamic_forward_proxy")
                    .setTypedConfig(
                        Any.pack(
                            ClusterConfig.newBuilder()
                                .setDnsCacheConfig(
                                    DnsCacheConfig.newBuilder()
                                        .setName("dynamic_forward_proxy_cache_config")
                                        .setDnsLookupFamily(properties.dynamicForwardProxy.dnsLookupFamily)
                                        .setHostTtl(
                                            Durations.fromMillis(
                                                properties.dynamicForwardProxy.maxHostTtl.toMillis()
                                            )
                                        )
                                        .setMaxHosts(
                                            UInt32Value.of(properties.dynamicForwardProxy.maxCachedHosts)
                                        )
                                )
                                .build()

                        )
                    )
            )
            .setTransportSocket(
                wrapTransportSocket(properties.dynamicForwardProxy.clusterName) {
                    TransportSocket.newBuilder()
                        .setName("envoy.transport_sockets.tls")
                        .setTypedConfig(
                            Any.pack(
                                UpstreamTlsContext.newBuilder()
                                    .setCommonTlsContext(
                                        CommonTlsContext.newBuilder()
                                            .setValidationContext(
                                                CertificateValidationContext.newBuilder()
                                                    .setTrustedCa(
                                                        DataSource.newBuilder().setFilename(properties.trustedCaFile)
                                                            .build()
                                                    )
                                            )
                                    ).build()
                            )
                        ).build()
                }
            ).build()
    }

    private fun wrapTransportSocket(clusterName: String, supplier: () -> TransportSocket): TransportSocket {
        return if (properties.tcpDumpsEnabled) {
            TransportSocket.newBuilder()
                .setName("envoy.transport_sockets.tap")
                .setTypedConfig(
                    Any.pack(
                        Tap.newBuilder().setCommonConfig(
                            CommonExtensionConfig.newBuilder().setAdminConfig(
                                AdminConfig.newBuilder().setConfigId("${clusterName}_tap")
                            )
                        ).setTransportSocket(supplier()).build()
                    )
                ).build()
        } else {
            supplier()
        }
    }

    private fun createMatchPlainText(clusterName: String) = Cluster.TransportSocketMatch.newBuilder()
        .setName("plaintext_match")
        .setTransportSocket(
            wrapTransportSocket(clusterName) {
                TransportSocket.newBuilder().setName("envoy.transport_sockets.raw_buffer").build()
            }
        )
        .build()
}
