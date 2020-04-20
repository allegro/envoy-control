package pl.allegro.tech.servicemesh.envoycontrol.snapshot

import io.envoyproxy.controlplane.cache.SnapshotResources
import io.envoyproxy.envoy.api.v2.Cluster
import io.envoyproxy.envoy.api.v2.ClusterLoadAssignment
import pl.allegro.tech.servicemesh.envoycontrol.services.LocalityAwareServicesState

data class GlobalSnapshot(
        val clusters: SnapshotResources<Cluster>,
        val allServicesGroupsClusters: Map<String, Cluster>,
        val endpoints: SnapshotResources<ClusterLoadAssignment>,
        val servicesStates: List<LocalityAwareServicesState> = listOf()
) {
    fun allClientEndpointsHaveTag(client: String, mtlsEnabledTag: String): Boolean {
        return servicesStates.flatMap {
            it.servicesState.serviceNameToInstances.filterKeys { serviceName -> serviceName == client }.values
        }.map { it.instances.all { it.tags.contains(mtlsEnabledTag) } }.all { it }
    }
}

internal fun globalSnapshot(
        clusters: Iterable<Cluster>,
        endpoints: Iterable<ClusterLoadAssignment>,
        properties: OutgoingPermissionsProperties = OutgoingPermissionsProperties(),
        servicesStates: List<LocalityAwareServicesState>
): GlobalSnapshot {
    val clusters = SnapshotResources.create(clusters, "")
    val allServicesGroupsClusters = getClustersForAllServicesGroups(clusters.resources(), properties)
    val endpoints = SnapshotResources.create(endpoints, "")
    return GlobalSnapshot(
        servicesStates = servicesStates,
        clusters = clusters,
        endpoints = endpoints,
        allServicesGroupsClusters = allServicesGroupsClusters
    )
}

private fun getClustersForAllServicesGroups(
    clusters: Map<String, Cluster>,
    properties: OutgoingPermissionsProperties
): Map<String, Cluster> {
    val blacklist = properties.allServicesDependencies.notIncludedByPrefix
    if (blacklist.isEmpty()) {
        return clusters
    } else {
        return clusters.filter { (serviceName) -> blacklist.none { serviceName.startsWith(it) } }
    }
}
