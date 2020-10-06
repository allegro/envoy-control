package pl.allegro.tech.servicemesh.envoycontrol.snapshot

import io.envoyproxy.controlplane.cache.SnapshotResources
import io.envoyproxy.envoy.config.cluster.v3.Cluster
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment

data class GlobalSnapshot(
    val clusters: SnapshotResources<Cluster>,
    val allServicesNames: Set<String>,
    val endpoints: SnapshotResources<ClusterLoadAssignment>,
    val clusterConfigurations: Map<String, ClusterConfiguration>,
    val securedClusters: SnapshotResources<Cluster>,
    val v3Clusters: SnapshotResources<Cluster>,
    val v3SecuredClusters: SnapshotResources<Cluster>
)

internal fun globalSnapshot(
    clusters: Iterable<Cluster>,
    endpoints: Iterable<ClusterLoadAssignment>,
    properties: OutgoingPermissionsProperties = OutgoingPermissionsProperties(),
    clusterConfigurations: Map<String, ClusterConfiguration>,
    securedClusters: List<Cluster>,
    v3Clusters: List<Cluster>,
    v3SecuredClusters: List<Cluster>
): GlobalSnapshot {
    val clusters = SnapshotResources.create(clusters, "")
    val securedClusters = SnapshotResources.create(securedClusters, "")
    val v3Clusters = SnapshotResources.create(v3Clusters, "")
    val v3SecuredClusters = SnapshotResources.create(v3SecuredClusters, "")
    val allServicesNames = getClustersForAllServicesGroups(clusters.resources(), properties)
    val endpoints = SnapshotResources.create(endpoints, "")
    return GlobalSnapshot(
        clusters = clusters,
        securedClusters = securedClusters,
        endpoints = endpoints,
        allServicesNames = allServicesNames,
        clusterConfigurations = clusterConfigurations,
        v3Clusters = v3Clusters,
        v3SecuredClusters = v3SecuredClusters
    )
}

private fun getClustersForAllServicesGroups(
    clusters: Map<String, Cluster>,
    properties: OutgoingPermissionsProperties
): Set<String> {
    val blacklist = properties.allServicesDependencies.notIncludedByPrefix
    if (blacklist.isEmpty()) {
        return clusters.keys
    } else {
        return clusters.filter { (serviceName) -> blacklist.none { serviceName.startsWith(it) } }.keys
    }
}
