package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.clusters

import io.envoyproxy.controlplane.cache.SnapshotResources
import io.envoyproxy.envoy.config.cluster.v3.Cluster
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment
import io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.groups.DependencySettings
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.GlobalSnapshot
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotProperties
import pl.allegro.tech.servicemesh.envoycontrol.utils.CLUSTER_NAME1
import pl.allegro.tech.servicemesh.envoycontrol.utils.CLUSTER_NAME2
import pl.allegro.tech.servicemesh.envoycontrol.utils.CURRENT_ZONE
import pl.allegro.tech.servicemesh.envoycontrol.utils.DEFAULT_CLUSTER_WEIGHTS
import pl.allegro.tech.servicemesh.envoycontrol.utils.DEFAULT_SERVICE_NAME
import pl.allegro.tech.servicemesh.envoycontrol.utils.TRAFFIC_SPLITTING_ZONE
import pl.allegro.tech.servicemesh.envoycontrol.utils.createAllServicesGroup
import pl.allegro.tech.servicemesh.envoycontrol.utils.createCluster
import pl.allegro.tech.servicemesh.envoycontrol.utils.createClusterConfigurations
import pl.allegro.tech.servicemesh.envoycontrol.utils.createEndpoints
import pl.allegro.tech.servicemesh.envoycontrol.utils.createListenersConfig
import pl.allegro.tech.servicemesh.envoycontrol.utils.createLoadAssignments
import pl.allegro.tech.servicemesh.envoycontrol.utils.createServicesGroup

internal class EnvoyClustersFactoryTest {

    companion object {
        private val factory = EnvoyClustersFactory(SnapshotProperties(), CURRENT_ZONE)
        private val snapshotPropertiesWithWeights = SnapshotProperties().apply {
            loadBalancing.trafficSplitting.weightsByService = mapOf(
                DEFAULT_SERVICE_NAME to DEFAULT_CLUSTER_WEIGHTS
            )
            loadBalancing.trafficSplitting.zoneName = TRAFFIC_SPLITTING_ZONE
            loadBalancing.trafficSplitting.zonesAllowingTrafficSplitting = listOf(CURRENT_ZONE)
        }
    }

    @Test
    fun `should get clusters for group`() {
        val snapshotProperties = SnapshotProperties()
        val cluster1 = createCluster(snapshotProperties, CLUSTER_NAME1)
        val result = factory.getClustersForGroup(
            createServicesGroup(
                snapshotProperties = snapshotProperties,
                dependencies = arrayOf(CLUSTER_NAME1 to null),
            ),
            createGlobalSnapshot(
                cluster1,
                createCluster(snapshotProperties, CLUSTER_NAME2),
            )
        )
        assertThat(result)
            .allSatisfy {
                assertThat(it).isEqualTo(cluster1)
            }
    }

    @Test
    fun `should get wildcard clusters for group`() {
        val snapshotProperties = SnapshotProperties()
        val cluster1 = createCluster(clusterName = CLUSTER_NAME1)
        val cluster2 = createCluster(clusterName = CLUSTER_NAME2)
        val result = factory.getClustersForGroup(
            createAllServicesGroup(
                snapshotProperties = snapshotProperties,
                defaultServiceSettings = DependencySettings()
            ),
            createGlobalSnapshot(cluster1, cluster2)
        )
        assertThat(result)
            .anySatisfy {
                assertThat(it).isEqualTo(cluster1)
            }.anySatisfy {
                assertThat(it).isEqualTo(cluster2)
            }
    }

    @Test
    fun `should get secured eds clusters for group`() {
        val snapshotProperties = SnapshotProperties()
        val cluster = createCluster(snapshotProperties, CLUSTER_NAME1)
        val securedCluster = createCluster(snapshotProperties, CLUSTER_NAME1, idleTimeout = 100)
        val result = factory.getClustersForGroup(
            createServicesGroup(
                snapshotProperties = snapshotProperties,
                listenersConfig = createListenersConfig(snapshotProperties, true),
                dependencies = arrayOf(CLUSTER_NAME1 to null),
            ),
            createGlobalSnapshot(
                cluster,
                securedClusters = listOf(securedCluster)
            )
        )
        assertThat(result).allSatisfy {
            assertThat(it).isEqualTo(securedCluster)
        }
    }

    @Test
    fun `should get cluster with locality weighted config for group clusters`() {
        val cluster1 = createCluster(snapshotPropertiesWithWeights, CLUSTER_NAME1)
        val factory = EnvoyClustersFactory(snapshotPropertiesWithWeights, CURRENT_ZONE)
        val result = factory.getClustersForGroup(
            createServicesGroup(
                snapshotProperties = snapshotPropertiesWithWeights,
                listenersConfig = createListenersConfig(snapshotPropertiesWithWeights, true),
                dependencies = arrayOf(CLUSTER_NAME1 to null),
            ),
            createGlobalSnapshot(cluster1)
        )
        assertThat(result)
            .anySatisfy {
                assertThat(it.name).isEqualTo(CLUSTER_NAME1)
                assertThat(it.edsClusterConfig).isEqualTo(cluster1.edsClusterConfig)
                assertThat(it.commonLbConfig).isNotNull
                assertThat(it.commonLbConfig.localityWeightedLbConfig).isNotNull
                assertThat(it.lbSubsetConfig).isNotNull
                assertThat(it.lbSubsetConfig.localityWeightAware).isTrue()
            }
    }

    @Test
    fun `should not apply locality weighted config if there are no endpoints in the ts zone`() {
        val cluster1 = createCluster(snapshotPropertiesWithWeights, CLUSTER_NAME1)
        val factory = EnvoyClustersFactory(snapshotPropertiesWithWeights, CURRENT_ZONE)
        val result = factory.getClustersForGroup(
            createServicesGroup(
                snapshotProperties = snapshotPropertiesWithWeights,
                listenersConfig = createListenersConfig(snapshotPropertiesWithWeights, true),
                dependencies = arrayOf(CLUSTER_NAME1 to null),
            ),
            createGlobalSnapshot(cluster1, endpoints = null)
        )
        assertThat(result)
            .anySatisfy {
                assertThat(it.name).isEqualTo(CLUSTER_NAME1)
                assertThat(it.edsClusterConfig).isEqualTo(cluster1.edsClusterConfig)
                assertThat(it.commonLbConfig.hasLocalityWeightedLbConfig()).isFalse()
                assertThat(it.lbSubsetConfig.localityWeightAware).isFalse()
            }
    }

    private fun createGlobalSnapshot(
        vararg clusters: Cluster,
        securedClusters: List<Cluster> = clusters.asList(),
        endpoints: List<LocalityLbEndpoints>? = createEndpoints()
    ): GlobalSnapshot {
        val clusterLoadAssignment = endpoints
            ?.let { createLoadAssignments(clusters.toList(), endpoints) }
            ?: createLoadAssignments(clusters.toList(), listOf())
        return GlobalSnapshot(
            SnapshotResources.create<Cluster>(clusters.toList(), "pl/allegro/tech/servicemesh/envoycontrol/v3")
                .resources(),
            clusters.map { it.name }.toSet(),
            SnapshotResources.create<ClusterLoadAssignment>(clusterLoadAssignment, "v1").resources(),
            createClusterConfigurations(),
            SnapshotResources.create<Cluster>(securedClusters, "v3").resources()
        )
    }
}
