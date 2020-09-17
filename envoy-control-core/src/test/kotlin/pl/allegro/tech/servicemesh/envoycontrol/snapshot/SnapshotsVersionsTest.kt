package pl.allegro.tech.servicemesh.envoycontrol.snapshot

import io.envoyproxy.envoy.api.v2.Cluster
import io.envoyproxy.envoy.api.v2.ClusterLoadAssignment
import io.envoyproxy.envoy.api.v2.core.Address
import io.envoyproxy.envoy.api.v2.core.SocketAddress
import io.envoyproxy.envoy.api.v2.endpoint.Endpoint
import io.envoyproxy.envoy.api.v2.endpoint.LbEndpoint
import io.envoyproxy.envoy.api.v2.endpoint.LocalityLbEndpoints
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.groups.AllServicesGroup
import pl.allegro.tech.servicemesh.envoycontrol.groups.ClientWithSelector
import pl.allegro.tech.servicemesh.envoycontrol.groups.CommunicationMode.XDS
import pl.allegro.tech.servicemesh.envoycontrol.groups.Incoming
import pl.allegro.tech.servicemesh.envoycontrol.groups.IncomingEndpoint
import pl.allegro.tech.servicemesh.envoycontrol.groups.Outgoing
import pl.allegro.tech.servicemesh.envoycontrol.groups.PathMatchingType
import pl.allegro.tech.servicemesh.envoycontrol.groups.ProxySettings
import pl.allegro.tech.servicemesh.envoycontrol.groups.Role

internal class SnapshotsVersionsTest {

    private val snapshotsVersions = SnapshotsVersions()

    private val group = AllServicesGroup(communicationMode = XDS)
    private val clusters = listOf(cluster(name = "service1"))
    private val endpoints = listOf(endpoints(clusterName = "service1", instances = 1))

    @Test
    fun `should generate a new version for a new group`() {
        // when
        val versions = snapshotsVersions.version(group, clusters, endpoints)

        // then
        assertThat(versions.clusters).isNotNull()
        assertThat(versions.endpoints).isNotNull()
    }

    @Test
    fun `should generate new version only for endpoints when they are different`() {
        // given
        val versions = snapshotsVersions.version(group, clusters, endpoints)

        // when
        val newEndpoints = listOf(endpoints(clusterName = "service1", instances = 2))
        val newVersions = snapshotsVersions.version(group, clusters, newEndpoints)

        // then
        assertThat(newVersions.clusters).isEqualTo(versions.clusters)
        assertThat(newVersions.endpoints).isNotEqualTo(versions.endpoints)
    }

    @Test
    fun `should generate new version for clusters and endpoints when clusters are different`() {
        // given
        val versions = snapshotsVersions.version(group, clusters, endpoints)

        // when
        val newClusters = listOf(cluster(name = "service1"), cluster(name = "service2"))
        val newVersions = snapshotsVersions.version(group, newClusters, endpoints)

        // then
        assertThat(newVersions.endpoints).isNotEqualTo(versions.endpoints)
        assertThat(newVersions.clusters).isNotEqualTo(versions.clusters)
    }

    @Test
    fun `should retain versions only for given groups`() {
        // given
        val versions = snapshotsVersions.version(group, clusters, endpoints)

        // when nothing changed but the group is not retained
        snapshotsVersions.retainGroups(emptyList())
        val newVersions = snapshotsVersions.version(group, clusters, endpoints)

        // then new version is generated even that clusters and endpoints are the same
        assertThat(newVersions.endpoints).isNotEqualTo(versions.endpoints)
        assertThat(newVersions.clusters).isNotEqualTo(versions.clusters)
    }

    @Test
    fun `should return same version for equal group when nothing changed`() {
        // given
        val version = snapshotsVersions.version(createGroup("/path"), clusters, endpoints)

        // when
        val newVersion = snapshotsVersions.version(createGroup("/path"), clusters, endpoints)

        // then
        assertThat(version).isEqualTo(newVersion)
    }

    @Test
    fun `should return different version for different group when nothing changed`() {
        // given
        val version = snapshotsVersions.version(createGroup("/path"), clusters, endpoints)

        // when
        val newVersion = snapshotsVersions.version(createGroup("/other-path"), clusters, endpoints)

        // then
        assertThat(version).isNotEqualTo(newVersion)
    }

    private fun cluster(name: String): Cluster {
        return Cluster.newBuilder()
            .setName(name)
            .build()
    }

    private fun endpoints(clusterName: String, instances: Int): ClusterLoadAssignment {
        return ClusterLoadAssignment.newBuilder()
            .addAllEndpoints(
                (0..instances).map { instance ->
                    LocalityLbEndpoints.newBuilder()
                        .addLbEndpoints(
                            LbEndpoint.newBuilder()
                                .setEndpoint(
                                    Endpoint.newBuilder()
                                        .setAddress(
                                            Address.newBuilder()
                                                .setSocketAddress(
                                                    SocketAddress.newBuilder()
                                                        .setAddress("127.0.0.1")
                                                        .setPortValue(instance)
                                                )
                                        )
                                )
                        )
                        .build()
                }
            )
            .setClusterName(clusterName)
            .build()
    }

    private fun createGroup(endpointPath: String): AllServicesGroup {
        return AllServicesGroup(
                communicationMode = XDS,
                serviceName = "name",
                proxySettings = ProxySettings(
                    incoming = Incoming(
                        endpoints = listOf(
                            IncomingEndpoint(
                                path = endpointPath,
                                pathMatchingType = PathMatchingType.PATH,
                                methods = setOf("GET", "PUT"),
                                clients = setOf(ClientWithSelector("client1"), ClientWithSelector("role1"))
                            )
                        ),
                        permissionsEnabled = true,
                        roles = listOf(
                            Role(
                                name = "role1",
                                clients = setOf(ClientWithSelector("client2"), ClientWithSelector("client3"))
                            )
                        )
                    ),
                    outgoing = Outgoing()
                )
        )
    }
}
