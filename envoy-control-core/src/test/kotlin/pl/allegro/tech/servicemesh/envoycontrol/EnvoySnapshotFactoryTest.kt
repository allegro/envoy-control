package pl.allegro.tech.servicemesh.envoycontrol

import com.google.protobuf.util.Durations
import io.envoyproxy.controlplane.cache.SnapshotResources
import io.envoyproxy.envoy.config.cluster.v3.Cluster
import io.envoyproxy.envoy.config.core.v3.AggregatedConfigSource
import io.envoyproxy.envoy.config.core.v3.ConfigSource
import io.envoyproxy.envoy.config.core.v3.Metadata
import io.envoyproxy.envoy.config.listener.v3.Listener
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.groups.AccessLogFilterSettings
import pl.allegro.tech.servicemesh.envoycontrol.groups.CommunicationMode
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group
import pl.allegro.tech.servicemesh.envoycontrol.groups.ListenersConfig
import pl.allegro.tech.servicemesh.envoycontrol.groups.ProxySettings
import pl.allegro.tech.servicemesh.envoycontrol.groups.ServicesGroup
import pl.allegro.tech.servicemesh.envoycontrol.groups.with
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.EnvoySnapshotFactory
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.GlobalSnapshot
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotsVersions
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.clusters.EnvoyClustersFactory
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.endpoints.EnvoyEndpointsFactory
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.EnvoyListenersFactory
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters.AccessLogFilterFactory
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters.EnvoyHttpFilters
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.routes.EnvoyEgressRoutesFactory
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.routes.EnvoyIngressRoutesFactory
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.routes.ServiceTagMetadataGenerator
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.serviceDependencies

class EnvoySnapshotFactoryTest {
    companion object {
        const val INGRESS_HOST = "ingress-host"
        const val INGRESS_PORT = 3380
        const val EGRESS_HOST = "egress-host"
        const val EGRESS_PORT = 3380
        const val CLUSTER_NAME = "cluster-name"
        const val DEFAULT_SERVICE_NAME = "service-name"
    }

    @Test
    fun shouldGetSnapshotListenersForGroupWhenDynamicListenersEnabled() {
        // given
        val properties = SnapshotProperties()
        val envoySnapshotFactory = createSnapshotFactory(properties)

        val group: Group = createServicesGroup()
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
    fun shouldGetEmptySnapshotListenersListForGroupWhenDynamicListenersPropertyIsNotEnabled() {
        // given
        val defaultProperties = SnapshotProperties().also { it.dynamicListeners.enabled = false }
        val envoySnapshotFactory = createSnapshotFactory(defaultProperties)

        val group: Group = createServicesGroup()
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

        val group: Group = createServicesGroup()
        val cluster = createCluster(defaultProperties)
        val globalSnapshot = createGlobalSnapshot(cluster)

        // when
        val snapshot = envoySnapshotFactory.getSnapshotForGroup(group, globalSnapshot)

        // then
        val listeners = snapshot.listeners().resources()
        assertThat(listeners.size).isEqualTo(0)
    }

    private fun createServicesGroup(
        mode: CommunicationMode = CommunicationMode.XDS,
        serviceName: String = DEFAULT_SERVICE_NAME,
        dependencies: Array<String> = emptyArray(),
        listenersConfigExists: Boolean = true
    ): ServicesGroup {
        val listenersConfig = when (listenersConfigExists) {
            true -> createListenersConfig()
            false -> null
        }
        return ServicesGroup(
            mode,
            serviceName,
            ProxySettings().with(serviceDependencies = serviceDependencies(*dependencies)),
            listenersConfig
        )
    }

    private fun createListenersConfig(): ListenersConfig {
        return ListenersConfig(
            ingressHost = INGRESS_HOST,
            ingressPort = INGRESS_PORT,
            egressHost = EGRESS_HOST,
            egressPort = EGRESS_PORT,
            accessLogFilterSettings = AccessLogFilterSettings(null, AccessLogFilterFactory())
        )
    }

    fun createSnapshotFactory(properties: SnapshotProperties): EnvoySnapshotFactory {
        val ingressRoutesFactory = EnvoyIngressRoutesFactory(
            SnapshotProperties(),
            EnvoyHttpFilters(
                emptyList(), emptyList(),
                Metadata.getDefaultInstance()
            )
        )
        val egressRoutesFactory = EnvoyEgressRoutesFactory(properties)
        val clustersFactory = EnvoyClustersFactory(properties)
        val endpointsFactory = EnvoyEndpointsFactory(properties, ServiceTagMetadataGenerator())
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

    private fun createGlobalSnapshot(cluster: Cluster?): GlobalSnapshot {
        return GlobalSnapshot(
            SnapshotResources.create(emptyList(), "pl/allegro/tech/servicemesh/envoycontrol/v3"), emptySet(),
            SnapshotResources.create(emptyList(), "v1"), emptyMap(),
            SnapshotResources.create(listOf(cluster), "v3"),
            SnapshotResources.create(emptyList(), "pl/allegro/tech/servicemesh/envoycontrol/v3"),
            SnapshotResources.create(emptyList(), "pl/allegro/tech/servicemesh/envoycontrol/v3")
        )
    }

    private fun createCluster(defaultProperties: SnapshotProperties): Cluster? {
        return Cluster.newBuilder().setName(CLUSTER_NAME)
            .setType(Cluster.DiscoveryType.EDS)
            .setConnectTimeout(Durations.fromMillis(defaultProperties.edsConnectionTimeout.toMillis()))
            .setEdsClusterConfig(
                Cluster.EdsClusterConfig.newBuilder().setEdsConfig(
                    ConfigSource.newBuilder().setAds(AggregatedConfigSource.newBuilder())
                ).setServiceName(DEFAULT_SERVICE_NAME)
            )
            .setLbPolicy(defaultProperties.loadBalancing.policy).build()
    }
}
