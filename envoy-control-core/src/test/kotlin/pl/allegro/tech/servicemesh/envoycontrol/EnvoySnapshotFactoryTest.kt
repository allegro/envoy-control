package pl.allegro.tech.servicemesh.envoycontrol

import io.envoyproxy.controlplane.cache.SnapshotResources
import io.envoyproxy.envoy.config.cluster.v3.Cluster
import io.envoyproxy.envoy.config.core.v3.Metadata
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment
import io.envoyproxy.envoy.config.listener.v3.Listener
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.groups.AccessLogFilterSettings
import pl.allegro.tech.servicemesh.envoycontrol.groups.AllServicesGroup
import pl.allegro.tech.servicemesh.envoycontrol.groups.CommunicationMode
import pl.allegro.tech.servicemesh.envoycontrol.groups.DependencySettings
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group
import pl.allegro.tech.servicemesh.envoycontrol.groups.IncomingRateLimitEndpoint
import pl.allegro.tech.servicemesh.envoycontrol.groups.ListenersConfig
import pl.allegro.tech.servicemesh.envoycontrol.groups.Outgoing
import pl.allegro.tech.servicemesh.envoycontrol.groups.PathNormalizationPolicy
import pl.allegro.tech.servicemesh.envoycontrol.groups.ProxySettings
import pl.allegro.tech.servicemesh.envoycontrol.groups.ServicesGroup
import pl.allegro.tech.servicemesh.envoycontrol.groups.with
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.EnvoySnapshotFactory
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.GlobalSnapshot
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotsVersions
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.outgoingTimeoutPolicy
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.clusters.EnvoyClustersFactory
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.endpoints.EnvoyEndpointsFactory
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.EnvoyListenersFactory
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters.EnvoyHttpFilters
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.routes.EnvoyEgressRoutesFactory
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.routes.EnvoyIngressRoutesFactory
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.routes.ServiceTagMetadataGenerator
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.serviceDependencies
import pl.allegro.tech.servicemesh.envoycontrol.utils.CLUSTER_NAME
import pl.allegro.tech.servicemesh.envoycontrol.utils.DEFAULT_CLUSTER_WEIGHTS
import pl.allegro.tech.servicemesh.envoycontrol.utils.DEFAULT_DISCOVERY_SERVICE_NAME
import pl.allegro.tech.servicemesh.envoycontrol.utils.DEFAULT_IDLE_TIMEOUT
import pl.allegro.tech.servicemesh.envoycontrol.utils.DEFAULT_PRIORITY
import pl.allegro.tech.servicemesh.envoycontrol.utils.DEFAULT_SERVICE_NAME
import pl.allegro.tech.servicemesh.envoycontrol.utils.EGRESS_HOST
import pl.allegro.tech.servicemesh.envoycontrol.utils.EGRESS_PORT
import pl.allegro.tech.servicemesh.envoycontrol.utils.HIGHEST_PRIORITY
import pl.allegro.tech.servicemesh.envoycontrol.utils.INGRESS_HOST
import pl.allegro.tech.servicemesh.envoycontrol.utils.INGRESS_PORT
import pl.allegro.tech.servicemesh.envoycontrol.utils.SNAPSHOT_PROPERTIES_WITH_WEIGHTS
import pl.allegro.tech.servicemesh.envoycontrol.utils.TRAFFIC_SPLITTING_ZONE
import pl.allegro.tech.servicemesh.envoycontrol.utils.createCluster
import pl.allegro.tech.servicemesh.envoycontrol.utils.createClusterConfigurations
import pl.allegro.tech.servicemesh.envoycontrol.utils.createEndpoints
import pl.allegro.tech.servicemesh.envoycontrol.utils.zoneWeights

class EnvoySnapshotFactoryTest {
    companion object {
        const val SERVICE_NAME_2 = "service-name-2"
        const val CURRENT_ZONE = "dc1"
    }

    @Test
    fun shouldGetSnapshotListenersForGroupWhenDynamicListenersEnabled() {
        // given
        val properties = SnapshotProperties()
        val envoySnapshotFactory = createSnapshotFactory(properties)

        val group: Group = createServicesGroup(snapshotProperties = properties)
        val cluster = createCluster(properties)
        val globalSnapshot = createGlobalSnapshot(cluster)

        // when
        val snapshot = envoySnapshotFactory.getSnapshotForGroup(group, globalSnapshot)

        // then
        val listeners = snapshot.listeners().resources()

        assertThat(listeners.size).isEqualTo(2)

        val ingressListenerResult: Listener? = snapshot.listeners().resources()["ingress_listener"]
        val ingressSocket = ingressListenerResult?.address?.socketAddress
        val ingressFilterChain = ingressListenerResult?.filterChainsList?.get(0)
        val egressListenerResult: Listener? = snapshot.listeners().resources()["egress_listener"]
        val egressSocket = egressListenerResult?.address?.socketAddress
        val egressFilterChain = egressListenerResult?.filterChainsList?.get(0)

        assertThat(ingressSocket?.address).isEqualTo(INGRESS_HOST)
        assertThat(ingressSocket?.portValue).isEqualTo(INGRESS_PORT)
        assertThat(ingressFilterChain?.filtersList?.get(0)?.name).isEqualTo("envoy.filters.network.http_connection_manager")
        assertThat(egressSocket?.address).isEqualTo(EGRESS_HOST)
        assertThat(egressSocket?.portValue).isEqualTo(EGRESS_PORT)
        assertThat(egressFilterChain?.filtersList?.get(0)?.name).isEqualTo("envoy.filters.network.http_connection_manager")
    }

    @Test
    fun shouldGetTLSUpgradeIgnorePrefixInFilterChainWhenConfigured() {
        // given
        val properties = SnapshotProperties().also {
            it.ignoreTLSUpgradeEnabled = true
        }
        val envoySnapshotFactory = createSnapshotFactory(properties)

        val group: Group = createServicesGroup(snapshotProperties = properties)
        val cluster = createCluster(properties)
        val globalSnapshot = createGlobalSnapshot(cluster)

        // when
        val snapshot = envoySnapshotFactory.getSnapshotForGroup(group, globalSnapshot)

        // then
        val listeners = snapshot.listeners().resources()

        assertThat(listeners.size).isEqualTo(2)

        val egressListenerResult: Listener? = snapshot.listeners().resources()["egress_listener"]
        val egressFilterChain = egressListenerResult?.filterChainsList?.get(0)
        val httpProtocolOptions = egressFilterChain?.filtersList?.get(0)?.typedConfig
            ?.unpack(io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager::class.java)
            ?.httpProtocolOptions
        assertThat(httpProtocolOptions?.ignoreHttp11UpgradeList?.firstOrNull()?.prefix).isEqualTo("TLS/")
    }

    @Test
    fun shouldGetEmptySnapshotListenersListForGroupWhenDynamicListenersPropertyIsNotEnabled() {
        // given
        val defaultProperties = SnapshotProperties().also { it.dynamicListeners.enabled = false }
        val envoySnapshotFactory = createSnapshotFactory(defaultProperties)

        val group: Group = createServicesGroup(snapshotProperties = defaultProperties)
        val cluster = createCluster(defaultProperties)
        val globalSnapshot = createGlobalSnapshot(cluster)

        // when
        val snapshot = envoySnapshotFactory.getSnapshotForGroup(group, globalSnapshot)

        // then
        val listeners = snapshot.listeners().resources()
        assertThat(listeners.size).isEqualTo(0)
    }

    @Test
    fun shouldGetEmptySnapshotListenersListForGroupWhenGroupListenersConfigIsNull() {
        // given
        val defaultProperties = SnapshotProperties().also { it.dynamicListeners.enabled = false }
        val envoySnapshotFactory = createSnapshotFactory(defaultProperties)

        val group: Group = createServicesGroup(snapshotProperties = defaultProperties)
        val cluster = createCluster(defaultProperties)
        val globalSnapshot = createGlobalSnapshot(cluster)

        // when
        val snapshot = envoySnapshotFactory.getSnapshotForGroup(group, globalSnapshot)

        // then
        val listeners = snapshot.listeners().resources()
        assertThat(listeners.size).isEqualTo(0)
    }

    @Test
    fun shouldGetSnapshotClustersWithDefaultConnectionIdleTimeout() {
        // given
        val defaultProperties = SnapshotProperties().also { it.dynamicListeners.enabled = false }
        val envoySnapshotFactory = createSnapshotFactory(defaultProperties)

        val cluster = createCluster(defaultProperties)
        val group: Group = createServicesGroup(
            dependencies = arrayOf(cluster.name to null),
            snapshotProperties = defaultProperties
        )
        val globalSnapshot = createGlobalSnapshot(cluster)

        // when
        val snapshot = envoySnapshotFactory.getSnapshotForGroup(group, globalSnapshot)

        // then
        val actualCluster = snapshot.clusters().resources()[CLUSTER_NAME]!!
        assertThat(actualCluster.commonHttpProtocolOptions.idleTimeout.seconds).isEqualTo(DEFAULT_IDLE_TIMEOUT)
    }

    @Test
    fun shouldGetSnapshotClustersWithCustomConnectionIdleTimeout() {
        // given
        val defaultProperties = SnapshotProperties().also { it.dynamicListeners.enabled = false }
        val envoySnapshotFactory = createSnapshotFactory(defaultProperties)

        val cluster = createCluster(defaultProperties)
        val group: Group = createServicesGroup(
            dependencies = arrayOf(cluster.name to outgoingTimeoutPolicy(connectionIdleTimeout = 10)),
            snapshotProperties = defaultProperties
        )
        val globalSnapshot = createGlobalSnapshot(cluster)

        // when
        val snapshot = envoySnapshotFactory.getSnapshotForGroup(group, globalSnapshot)

        // then
        val actualCluster = snapshot.clusters().resources()[CLUSTER_NAME]!!
        assertThat(actualCluster.commonHttpProtocolOptions.idleTimeout.seconds).isEqualTo(10)
    }

    @Test
    fun shouldGetSnapshotClustersWithCustomConnectionIdleTimeoutFromWildcardDependency() {
        // given
        val defaultProperties = SnapshotProperties().also { it.dynamicListeners.enabled = false }
        val envoySnapshotFactory = createSnapshotFactory(defaultProperties)

        val wildcardTimeoutPolicy = outgoingTimeoutPolicy(connectionIdleTimeout = 12)
        val cluster = createCluster(defaultProperties)
        val group: Group = createAllServicesGroup(
            dependencies = arrayOf(cluster.name to null, "*" to wildcardTimeoutPolicy),
            defaultServiceSettings = DependencySettings(timeoutPolicy = wildcardTimeoutPolicy),
            snapshotProperties = defaultProperties
        )
        val globalSnapshot = createGlobalSnapshot(cluster)

        // when
        val snapshot = envoySnapshotFactory.getSnapshotForGroup(group, globalSnapshot)

        // then
        val actualCluster = snapshot.clusters().resources()[cluster.name]!!
        assertThat(actualCluster.commonHttpProtocolOptions.idleTimeout.seconds).isEqualTo(12)
    }

    @Test
    fun shouldGetSnapshotClustersWithCustomConnectionIdleTimeoutDespiteWildcardDependency() {
        // given
        val defaultProperties = SnapshotProperties().also { it.dynamicListeners.enabled = false }
        val envoySnapshotFactory = createSnapshotFactory(defaultProperties)

        val wildcardTimeoutPolicy = outgoingTimeoutPolicy(connectionIdleTimeout = 12)
        val cluster1 = createCluster(defaultProperties)
        val cluster2 = createCluster(defaultProperties, clusterName = "cluster-name-2")
        val group: Group = createAllServicesGroup(
            dependencies = arrayOf(
                cluster1.name to outgoingTimeoutPolicy(connectionIdleTimeout = 1),
                cluster2.name to null,
                "*" to wildcardTimeoutPolicy
            ),
            defaultServiceSettings = DependencySettings(timeoutPolicy = wildcardTimeoutPolicy),
            snapshotProperties = defaultProperties
        )
        val globalSnapshot = createGlobalSnapshot(cluster1, cluster2)

        // when
        val snapshot = envoySnapshotFactory.getSnapshotForGroup(group, globalSnapshot)

        // then
        val actualCluster1 = snapshot.clusters().resources()[cluster1.name]!!
        val actualCluster2 = snapshot.clusters().resources()[cluster2.name]!!

        assertThat(actualCluster1.commonHttpProtocolOptions.idleTimeout.seconds).isEqualTo(1)
        assertThat(actualCluster2.commonHttpProtocolOptions.idleTimeout.seconds).isEqualTo(12)
    }

    @Test
    fun `should get regular snapshot cluster when there are no traffic splitting settings for zone`() {
        val snapshotProperties = SNAPSHOT_PROPERTIES_WITH_WEIGHTS.also {
            it.loadBalancing.trafficSplitting.zoneName = "not-matching-dc"
        }
        val envoySnapshotFactory = createSnapshotFactory(snapshotProperties)
        val cluster1 = createCluster(snapshotProperties, clusterName = DEFAULT_SERVICE_NAME)
        val cluster2 = createCluster(snapshotProperties, clusterName = SERVICE_NAME_2)
        val group: Group = createServicesGroup(
            dependencies = arrayOf(SERVICE_NAME_2 to null),
            snapshotProperties = snapshotProperties
        )
        val globalSnapshot = createGlobalSnapshot(cluster1, cluster2)
        val snapshot = envoySnapshotFactory.getSnapshotForGroup(group, globalSnapshot)

        assertThat(snapshot.clusters().resources().values)
            .allSatisfy { !it.hasCommonLbConfig() || !it.commonLbConfig.hasLocalityWeightedLbConfig() }
            .hasSize(1)
    }

    @Test
    fun `should get cluster with locality weighted config when there are traffic splitting settings for zone`() {
        val envoySnapshotFactory = createSnapshotFactory(SNAPSHOT_PROPERTIES_WITH_WEIGHTS)
        val cluster1 = createCluster(SNAPSHOT_PROPERTIES_WITH_WEIGHTS, clusterName = DEFAULT_SERVICE_NAME)
        val cluster2 = createCluster(SNAPSHOT_PROPERTIES_WITH_WEIGHTS, clusterName = SERVICE_NAME_2)
        val group: Group = createServicesGroup(
            dependencies = arrayOf(SERVICE_NAME_2 to null),
            snapshotProperties = SNAPSHOT_PROPERTIES_WITH_WEIGHTS
        )
        val globalSnapshot = createGlobalSnapshot(cluster1, cluster2)
        val snapshot = envoySnapshotFactory.getSnapshotForGroup(group, globalSnapshot)

        assertThat(snapshot.clusters().resources().values)
            .anySatisfy { it.hasCommonLbConfig() && it.commonLbConfig.hasLocalityWeightedLbConfig() }
            .hasSize(1)
    }

    @Test
    fun `should get weighted locality lb endpoints when there are traffic splitting settings for zone`() {
        val envoySnapshotFactory = createSnapshotFactory(SNAPSHOT_PROPERTIES_WITH_WEIGHTS)
        val cluster1 = createCluster(SNAPSHOT_PROPERTIES_WITH_WEIGHTS, clusterName = DEFAULT_SERVICE_NAME)
        val cluster2 = createCluster(SNAPSHOT_PROPERTIES_WITH_WEIGHTS, clusterName = SERVICE_NAME_2)
        val group: Group = createServicesGroup(
            dependencies = arrayOf(SERVICE_NAME_2 to null),
            snapshotProperties = SNAPSHOT_PROPERTIES_WITH_WEIGHTS
        )
        val globalSnapshot = createGlobalSnapshot(cluster1, cluster2)
        val snapshot = envoySnapshotFactory.getSnapshotForGroup(group, globalSnapshot)

        assertThat(snapshot.endpoints().resources().values)
            .anySatisfy {
                assertThat(it.endpointsList)
                    .anySatisfy { e ->
                        e.locality.zone == TRAFFIC_SPLITTING_ZONE &&
                            e.loadBalancingWeight.value == DEFAULT_CLUSTER_WEIGHTS.weightByZone[TRAFFIC_SPLITTING_ZONE] &&
                            e.priority == DEFAULT_PRIORITY
                    }.anySatisfy { e ->
                        e.locality.zone == TRAFFIC_SPLITTING_ZONE &&
                            e.loadBalancingWeight.value == DEFAULT_CLUSTER_WEIGHTS.weightByZone[TRAFFIC_SPLITTING_ZONE] &&
                            e.priority == HIGHEST_PRIORITY
                    }
                    .anySatisfy { e ->
                        e.locality.zone == CURRENT_ZONE &&
                            e.loadBalancingWeight.value == DEFAULT_CLUSTER_WEIGHTS.weightByZone[CURRENT_ZONE]
                    }
                    .hasSize(3)
            }
    }

    @Test
    fun `should not set weight to locality lb endpoints when there are no matching weight settings`() {
        val defaultProperties = SNAPSHOT_PROPERTIES_WITH_WEIGHTS.also {
            it.loadBalancing.trafficSplitting.weightsByService = mapOf(
                DEFAULT_SERVICE_NAME to zoneWeights(mapOf(CURRENT_ZONE to 60))
            )
        }
        val envoySnapshotFactory = createSnapshotFactory(defaultProperties)
        val cluster1 = createCluster(defaultProperties, clusterName = DEFAULT_SERVICE_NAME)
        val cluster2 = createCluster(defaultProperties, clusterName = SERVICE_NAME_2)
        val group: Group = createServicesGroup(
            dependencies = arrayOf(SERVICE_NAME_2 to null),
            snapshotProperties = defaultProperties
        )
        val globalSnapshot = createGlobalSnapshot(cluster1, cluster2)
        val snapshot = envoySnapshotFactory.getSnapshotForGroup(group, globalSnapshot)

        assertThat(snapshot.endpoints().resources().values)
            .anySatisfy {
                assertThat(it.endpointsList)
                    .anySatisfy { e ->
                        e.locality.zone == CURRENT_ZONE &&
                            e.loadBalancingWeight.value == DEFAULT_CLUSTER_WEIGHTS.weightByZone[CURRENT_ZONE]
                    }
                    .anySatisfy { e ->
                        e.locality.zone == TRAFFIC_SPLITTING_ZONE &&
                            !e.hasLoadBalancingWeight()
                    }
                    .hasSize(3)
            }
    }

    @Test
    fun `should fetch ratelimit service endpoint if there are global rate limits`() {
        // given
        val properties = SnapshotProperties().apply { rateLimit.serviceName = "rl_service" }
        val envoySnapshotFactory = createSnapshotFactory(properties)

        val group: Group = createServicesGroup(
            rateLimitEndpoints = listOf(
                IncomingRateLimitEndpoint("/hello", rateLimit = "12/s")
            ),
            snapshotProperties = properties
        )
        val cluster = createCluster(properties)
        val globalSnapshot = createGlobalSnapshot(cluster).withEndpoint("rl_service")

        // when
        val snapshot = envoySnapshotFactory.getSnapshotForGroup(group, globalSnapshot)

        // then
        assertThat(snapshot.endpoints().resources()).containsKey("rl_service")
    }

    @Test
    fun `should not fetch ratelimit service endpoint if there are no global rate limits`() {
        // given
        val properties = SnapshotProperties().apply { rateLimit.serviceName = "rl_service" }
        val envoySnapshotFactory = createSnapshotFactory(properties)

        val group: Group = createServicesGroup(rateLimitEndpoints = emptyList(), snapshotProperties = properties)
        val cluster = createCluster(properties)
        val globalSnapshot = createGlobalSnapshot(cluster).withEndpoint("rl_service")

        // when
        val snapshot = envoySnapshotFactory.getSnapshotForGroup(group, globalSnapshot)

        // then
        assertThat(snapshot.endpoints().resources()).doesNotContainKey("rl_service")
    }

    @Test
    fun `should not fetch ratelimit service endpoint if there are global rate limits, but rate limit service does not exist`() {
        // given
        val properties = SnapshotProperties().apply { rateLimit.serviceName = "rl_service" }
        val envoySnapshotFactory = createSnapshotFactory(properties)

        val group: Group = createServicesGroup(rateLimitEndpoints = emptyList(), snapshotProperties = properties)
        val cluster = createCluster(properties)
        val globalSnapshot = createGlobalSnapshot(cluster)

        // when
        val snapshot = envoySnapshotFactory.getSnapshotForGroup(group, globalSnapshot)

        // then
        assertThat(snapshot.endpoints().resources()).doesNotContainKey("rl_service")
    }

    private fun GlobalSnapshot.withEndpoint(clusterName: String): GlobalSnapshot = copy(
        endpoints = SnapshotResources.create<ClusterLoadAssignment>(
            listOf(
                ClusterLoadAssignment.newBuilder()
                    .setClusterName(clusterName)
                    .build()
            ), "v1"
        ).resources()
    )

    private fun createServicesGroup(
        mode: CommunicationMode = CommunicationMode.XDS,
        serviceName: String = DEFAULT_SERVICE_NAME,
        discoveryServiceName: String = DEFAULT_DISCOVERY_SERVICE_NAME,
        dependencies: Array<Pair<String, Outgoing.TimeoutPolicy?>> = emptyArray(),
        listenersConfigExists: Boolean = true,
        rateLimitEndpoints: List<IncomingRateLimitEndpoint> = emptyList(),
        snapshotProperties: SnapshotProperties
    ): ServicesGroup {
        val listenersConfig = when (listenersConfigExists) {
            true -> createListenersConfig(snapshotProperties)
            false -> null
        }
        return ServicesGroup(
            communicationMode = mode,
            serviceName = serviceName,
            discoveryServiceName = discoveryServiceName,
            proxySettings = ProxySettings().with(
                serviceDependencies = serviceDependencies(*dependencies),
                rateLimitEndpoints = rateLimitEndpoints
            ),
            pathNormalizationPolicy = PathNormalizationPolicy(),
            listenersConfig = listenersConfig
        )
    }

    private fun createAllServicesGroup(
        mode: CommunicationMode = CommunicationMode.XDS,
        serviceName: String = DEFAULT_SERVICE_NAME,
        discoveryServiceName: String = DEFAULT_DISCOVERY_SERVICE_NAME,
        dependencies: Array<Pair<String, Outgoing.TimeoutPolicy?>> = emptyArray(),
        defaultServiceSettings: DependencySettings,
        listenersConfigExists: Boolean = true,
        snapshotProperties: SnapshotProperties
    ): AllServicesGroup {
        val listenersConfig = when (listenersConfigExists) {
            true -> createListenersConfig(snapshotProperties)
            false -> null
        }
        return AllServicesGroup(
            communicationMode = mode,
            serviceName = serviceName,
            discoveryServiceName = discoveryServiceName,
            proxySettings = ProxySettings().with(
                serviceDependencies = serviceDependencies(*dependencies),
                defaultServiceSettings = defaultServiceSettings
            ),
            pathNormalizationPolicy = PathNormalizationPolicy(),
            listenersConfig = listenersConfig
        )
    }

    private fun createListenersConfig(snapshotProperties: SnapshotProperties): ListenersConfig {
        return ListenersConfig(
            ingressHost = INGRESS_HOST,
            ingressPort = INGRESS_PORT,
            egressHost = EGRESS_HOST,
            egressPort = EGRESS_PORT,
            accessLogFilterSettings = AccessLogFilterSettings(
                null,
                snapshotProperties.dynamicListeners.httpFilters.accessLog.filters
            )
        )
    }

    fun createSnapshotFactory(properties: SnapshotProperties): EnvoySnapshotFactory {
        val ingressRoutesFactory = EnvoyIngressRoutesFactory(
            SnapshotProperties(),
            EnvoyHttpFilters(
                emptyList(), emptyList()
            ) { _, _ -> Metadata.getDefaultInstance() },
            CURRENT_ZONE
        )
        val egressRoutesFactory = EnvoyEgressRoutesFactory(properties)
        val clustersFactory = EnvoyClustersFactory(properties, CURRENT_ZONE)
        val endpointsFactory = EnvoyEndpointsFactory(properties, ServiceTagMetadataGenerator(), CURRENT_ZONE)
        val envoyHttpFilters = EnvoyHttpFilters.defaultFilters(properties)
        val listenersFactory = EnvoyListenersFactory(properties, envoyHttpFilters)
        val snapshotsVersions = SnapshotsVersions()
        val meterRegistry: MeterRegistry = SimpleMeterRegistry()

        return EnvoySnapshotFactory(
            ingressRoutesFactory,
            egressRoutesFactory,
            clustersFactory,
            endpointsFactory,
            listenersFactory,
            snapshotsVersions,
            properties,
            meterRegistry
        )
    }

    private fun createGlobalSnapshot(vararg clusters: Cluster): GlobalSnapshot {
        return GlobalSnapshot(
            SnapshotResources.create<Cluster>(clusters.toList(), "pl/allegro/tech/servicemesh/envoycontrol/v3")
                .resources(),
            clusters.map { it.name }.toSet(),
            SnapshotResources.create<ClusterLoadAssignment>(createLoadAssignments(clusters.toList()), "v1").resources(),
            createClusterConfigurations(),
            SnapshotResources.create<Cluster>(clusters.toList(), "v3").resources()
        )
    }

    private fun createLoadAssignments(clusters: List<Cluster>): List<ClusterLoadAssignment> {
        return clusters.map {
            ClusterLoadAssignment.newBuilder()
                .setClusterName(it.name)
                .addAllEndpoints(createEndpoints())
                .build()
        }
    }
}
