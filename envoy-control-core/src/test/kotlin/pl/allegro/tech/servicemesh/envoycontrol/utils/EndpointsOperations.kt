package pl.allegro.tech.servicemesh.envoycontrol.utils

import io.envoyproxy.envoy.config.cluster.v3.Cluster
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint
import io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints

fun createLoadAssignments(clusters: List<Cluster>): List<ClusterLoadAssignment> {
    return clusters.map {
        ClusterLoadAssignment.newBuilder()
            .setClusterName(it.name)
            .addAllEndpoints(createEndpoints())
            .build()
    }
}

fun createEndpoints(): List<LocalityLbEndpoints> =
    listOf(
        createEndpoint(CURRENT_ZONE),
        createEndpoint(TRAFFIC_SPLITTING_FORCE_TRAFFIC_ZONE)
    )

fun createEndpoint(zone: String): LocalityLbEndpoints {
    return LocalityLbEndpoints.newBuilder()
        .setLocality(
            io.envoyproxy.envoy.config.core.v3.Locality
                .newBuilder()
                .setZone(zone)
                .build()
        )
        .addAllLbEndpoints(listOf(LbEndpoint.getDefaultInstance()))
        .build()
}
