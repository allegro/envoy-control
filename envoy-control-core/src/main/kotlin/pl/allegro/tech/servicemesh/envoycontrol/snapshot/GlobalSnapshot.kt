package pl.allegro.tech.servicemesh.envoycontrol.snapshot

import io.envoyproxy.controlplane.cache.SnapshotResources
import io.envoyproxy.envoy.config.cluster.v3.Cluster
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment
import pl.allegro.tech.servicemesh.envoycontrol.groups.Outgoing
import pl.allegro.tech.servicemesh.envoycontrol.groups.TagDependency

typealias ClusterName = String

data class GlobalSnapshot(
    val clusters: Map<ClusterName, Cluster>,
    val allServicesNames: Set<ClusterName>,
    val endpoints: Map<ClusterName, ClusterLoadAssignment>,
    val clusterConfigurations: Map<ClusterName, ClusterConfiguration>,
    val securedClusters: Map<ClusterName, Cluster>,
    val tags: Map<ClusterName, Set<String>>
) {
    fun getTagsForDependency(
        outgoing: Outgoing
    ): List<Pair<ClusterName, TagDependency>> {
        val serviceDependencies = outgoing.getServiceDependencies().map { it.service }.toSet()
        return outgoing.getTagDependencies().flatMap { tagDependency ->
            tags.filterKeys { !serviceDependencies.contains(it) }
                .filterValues { it.contains(tagDependency.tag) }
                .map { it.key to tagDependency }
        }
    }
}

@Suppress("LongParameterList")
fun globalSnapshot(
    clusters: Iterable<Cluster> = emptyList(),
    endpoints: Iterable<ClusterLoadAssignment> = emptyList(),
    properties: OutgoingPermissionsProperties = OutgoingPermissionsProperties(),
    clusterConfigurations: Map<ClusterName, ClusterConfiguration> = emptyMap(),
    securedClusters: List<Cluster> = emptyList(),
    tags: Map<ClusterName, Set<String>>
): GlobalSnapshot {
    val clusters = SnapshotResources.create<Cluster>(clusters, "").resources()
    val securedClusters = SnapshotResources.create<Cluster>(securedClusters, "").resources()
    val allServicesNames = getClustersForAllServicesGroups(clusters, properties)
    val endpoints = SnapshotResources.create<ClusterLoadAssignment>(endpoints, "").resources()
    return GlobalSnapshot(
        clusters = clusters,
        securedClusters = securedClusters,
        endpoints = endpoints,
        allServicesNames = allServicesNames,
        clusterConfigurations = clusterConfigurations,
        tags = tags
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
