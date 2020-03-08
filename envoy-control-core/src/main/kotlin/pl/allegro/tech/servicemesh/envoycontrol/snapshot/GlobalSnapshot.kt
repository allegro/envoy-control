package pl.allegro.tech.servicemesh.envoycontrol.snapshot

import io.envoyproxy.controlplane.cache.SnapshotResources
import io.envoyproxy.envoy.api.v2.Cluster
import io.envoyproxy.envoy.api.v2.ClusterLoadAssignment

data class GlobalSnapshot(
    val clusters: SnapshotResources<Cluster>,
    val endpoints: SnapshotResources<ClusterLoadAssignment>
) {
    constructor(
        clusters: Iterable<Cluster>,
        endpoints: Iterable<ClusterLoadAssignment>
    ): this(
        clusters = SnapshotResources.create(clusters, ""),
        endpoints = SnapshotResources.create(endpoints, "")
    )
}
