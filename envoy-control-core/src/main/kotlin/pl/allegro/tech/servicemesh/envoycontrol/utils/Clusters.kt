package pl.allegro.tech.servicemesh.envoycontrol.utils

import com.google.protobuf.UInt32Value
import io.envoyproxy.envoy.config.route.v3.WeightedCluster
import io.envoyproxy.envoy.config.route.v3.WeightedCluster.ClusterWeight

fun WeightedCluster.Builder.withClusterWeight(clusterName: String, weight: Int): WeightedCluster.Builder {
    this.addClusters(
        ClusterWeight.newBuilder()
            .setName(clusterName)
            .setWeight(UInt32Value.of(weight))
            .build()
    )
    return this
}
