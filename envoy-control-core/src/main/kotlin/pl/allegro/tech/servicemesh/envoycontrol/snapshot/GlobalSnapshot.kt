package pl.allegro.tech.servicemesh.envoycontrol.snapshot

import io.envoyproxy.controlplane.cache.SnapshotResources
import io.envoyproxy.envoy.api.v2.Cluster
import io.envoyproxy.envoy.api.v2.ClusterLoadAssignment

data class GlobalSnapshot(
    val clusters: SnapshotResources<Cluster>,
    val allServicesGroupsClusters: Map<String, Cluster>,
    val endpoints: SnapshotResources<ClusterLoadAssignment>
)

internal fun globalSnapshot(
    clusters: Iterable<Cluster>,
    endpoints: Iterable<ClusterLoadAssignment>,
    properties: OutgoingPermissionsProperties = OutgoingPermissionsProperties()
): GlobalSnapshot {
    val clusters = SnapshotResources.create(clusters, "")
    val allServicesGroupsClusters = getClustersForAllServicesGroups(clusters.resources(), properties)
    val endpoints = SnapshotResources.create(endpoints, "")
    return GlobalSnapshot(
        clusters = clusters,
        endpoints = endpoints,
        allServicesGroupsClusters = allServicesGroupsClusters
    )
}

private fun getClustersForAllServicesGroups(
    clusters: Map<String, Cluster>,
    properties: OutgoingPermissionsProperties
): Map<String, Cluster> {
    val blacklist = properties.servicesNotIncludedInWildcardByPrefix
    if (blacklist.isEmpty()) {
        return clusters
    } else {
        return clusters.filter { (serviceName) -> blacklist.none { serviceName.startsWith(it) } }
    }
}
