package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.clusters

import io.envoyproxy.envoy.config.cluster.v3.Cluster
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.ObjectAssert
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.groups.AllServicesGroup
import pl.allegro.tech.servicemesh.envoycontrol.groups.CommunicationMode
import pl.allegro.tech.servicemesh.envoycontrol.groups.Outgoing
import pl.allegro.tech.servicemesh.envoycontrol.groups.ProxySettings
import pl.allegro.tech.servicemesh.envoycontrol.groups.ServicesGroup
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.GlobalSnapshot
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.createClusters
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.serviceDependency
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.tagDependency

class EnvoyClusterFactoryTest {

    companion object {
        val allServicesGroup = AllServicesGroup(
            communicationMode = CommunicationMode.ADS,
            serviceName = "service-name",
            discoveryServiceName = "service-name"
        )

        val serviceGroup = ServicesGroup(
            communicationMode = CommunicationMode.ADS,
            serviceName = "service-name",
            discoveryServiceName = "service-name"
        )
    }

    @Test
    fun `should return cluster from service dependency`() {
        // given
        val properties = SnapshotProperties()
        val factory = EnvoyClustersFactory(properties)
        val group = serviceGroup.copy(
            proxySettings = ProxySettings(
                outgoing = Outgoing(
                    serviceDependencies = listOf(serviceDependency("service-A", 33))
                )
            )
        )
        val services = listOf("service-A", "service-B", "service-C")
        val globalSnapshot = buildGlobalSnapshot(
            services = services,
            properties = properties
        )

        // when
        val clustersForGroup = factory.getClustersForGroup(group, globalSnapshot)

        // then
        assertThat(clustersForGroup)
            .hasSize(1)
            .first()
            .matches { it.name == "service-A" }
            .hasIdleTimeout(33)
    }

    @Test
    fun `should return clusters from tag dependency`() {
        // given
        val properties = SnapshotProperties()
        val factory = EnvoyClustersFactory(properties)
        val group = serviceGroup.copy(
            proxySettings = ProxySettings(
                outgoing = Outgoing(
                    tagDependencies = listOf(tagDependency("tag", 33))
                )
            )
        )
        val services = listOf("service-A", "service-B", "service-C")
        val globalSnapshot = buildGlobalSnapshot(
            services = services,
            properties = properties,
            tags = mapOf("service-A" to setOf("tag"), "service-C" to setOf("tag"))
        )

        // when
        val clustersForGroup = factory.getClustersForGroup(group, globalSnapshot)

        // then
        assertThat(clustersForGroup)
            .hasSize(2)
            .extracting<String> { it.name }
            .containsAll(listOf("service-A", "service-C"))
        clustersForGroup.assertServiceCluster("service-A")
            .hasIdleTimeout(33)
        clustersForGroup.assertServiceCluster("service-C")
            .hasIdleTimeout(33)
    }

    @Test
    fun `should return clusters from tag dependency with keeping order`() {
        // given
        val properties = SnapshotProperties()
        val factory = EnvoyClustersFactory(properties)
        val group = serviceGroup.copy(
            proxySettings = ProxySettings(
                outgoing = Outgoing(
                    tagDependencies = listOf(
                        tagDependency("tag-1", 33),
                        tagDependency("tag-2", 27))
                )
            )
        )
        val services = listOf("service-A", "service-B", "service-C")
        val globalSnapshot = buildGlobalSnapshot(
            services = services,
            properties = properties,
            tags = mapOf("service-A" to setOf("tag-1"), "service-C" to setOf("tag-1", "tag-2"), "service-B" to setOf("tag-2"))
        )

        // when
        val clustersForGroup = factory.getClustersForGroup(group, globalSnapshot)

        // then
        assertThat(clustersForGroup)
            .hasSize(3)
            .extracting<String> { it.name }
            .containsAll(services)

        clustersForGroup.assertServiceCluster("service-A")
            .hasIdleTimeout(33)
        clustersForGroup.assertServiceCluster("service-B")
            .hasIdleTimeout(27)
        clustersForGroup.assertServiceCluster("service-C")
            .hasIdleTimeout(33)
    }

    @Test
    fun `should return correct configuration for clusters from tag dependency where one service has multiple tags`() {
        // given
        val properties = SnapshotProperties()
        val factory = EnvoyClustersFactory(properties)
        val group = serviceGroup.copy(
            proxySettings = ProxySettings(
                outgoing = Outgoing(
                    serviceDependencies = listOf(
                        serviceDependency("service-A", 44)
                    ),
                    tagDependencies = listOf(
                        tagDependency("tag", 33)
                    )
                )
            )
        )
        val services = listOf("service-A", "service-B", "service-C")
        val globalSnapshot = buildGlobalSnapshot(
            services = services,
            properties = properties,
            tags = mapOf("service-A" to setOf("tag"), "service-C" to setOf("tag"))
        )

        // when
        val clustersForGroup = factory.getClustersForGroup(group, globalSnapshot)

        // then
        assertThat(clustersForGroup)
            .hasSize(2)
            .extracting<String> { it.name }
            .containsAll(listOf("service-A", "service-C"))
        clustersForGroup.assertServiceCluster("service-A")
            .hasIdleTimeout(44)
        clustersForGroup.assertServiceCluster("service-C")
            .hasIdleTimeout(33)
    }

    @Test
    fun `should return all clusters when is AllServiceGroup`() {
        // given
        val properties = SnapshotProperties()
        val factory = EnvoyClustersFactory(properties)
        val group = allServicesGroup
        val services = listOf("service-A", "service-B", "service-C")
        val globalSnapshot = buildGlobalSnapshot(
            services = services,
            properties = properties
        )

        // when
        val clustersForGroup = factory.getClustersForGroup(group, globalSnapshot)

        // then
        assertThat(clustersForGroup)
            .hasSize(3)
            .extracting<String> { it.name }
            .containsAll(services)
    }

    @Test
    fun `should return all clusters when is AllServiceGroup and has service dependency`() {
        val properties = SnapshotProperties()
        val factory = EnvoyClustersFactory(properties)
        val group = allServicesGroup.copy(
            proxySettings = ProxySettings(
                outgoing = Outgoing(
                    serviceDependencies = listOf(
                        serviceDependency("service-A", 34)
                    )
                )
            )
        )
        val services = listOf("service-A", "service-B", "service-C")
        val globalSnapshot = buildGlobalSnapshot(
            services = services,
            properties = properties
        )

        // when
        val clustersForGroup = factory.getClustersForGroup(group, globalSnapshot)

        // then
        assertThat(clustersForGroup)
            .hasSize(3)
            .extracting<String> { it.name }
            .containsAll(services)
        clustersForGroup.assertServiceCluster("service-A")
            .hasIdleTimeout(34)
    }

    @Test
    fun `should return all clusters when is AllServiceGroup and has tag dependency`() {
        val properties = SnapshotProperties()
        val factory = EnvoyClustersFactory(properties)
        val group = allServicesGroup.copy(
            proxySettings = ProxySettings(
                outgoing = Outgoing(
                    tagDependencies = listOf(
                        tagDependency("tag", 27)
                    )
                )
            )
        )
        val services = listOf("service-A", "service-B", "service-C")
        val globalSnapshot = buildGlobalSnapshot(
            services = services,
            properties = properties,
            tags = mapOf("service-A" to setOf("tag")))

        // when
        val clustersForGroup = factory.getClustersForGroup(group, globalSnapshot)

        // then
        assertThat(clustersForGroup)
            .hasSize(3)
            .extracting<String> { it.name }
            .containsAll(services)
        clustersForGroup.assertServiceCluster("service-A")
            .hasIdleTimeout(27)
    }
}

private fun buildGlobalSnapshot(
    services: Collection<String> = emptyList(),
    properties: SnapshotProperties = SnapshotProperties(),
    tags: Map<String, Set<String>> = emptyMap()
) = GlobalSnapshot(
    clusters = createClusters(properties, services.toList()),
    allServicesNames = services.toSet(),
    endpoints = emptyMap(),
    clusterConfigurations = emptyMap(),
    securedClusters = emptyMap(),
    tags = tags
)

private fun List<Cluster>.assertServiceCluster(name: String): ObjectAssert<Cluster> {
    return assertThat(this)
        .filteredOn { it.name == name }
        .hasSize(1)
        .first()
}

private fun ObjectAssert<Cluster>.hasIdleTimeout(idleTimeout: Long): ObjectAssert<Cluster> {
    this.extracting { it.commonHttpProtocolOptions.idleTimeout.seconds }
        .isEqualTo(idleTimeout)
    return this
}
