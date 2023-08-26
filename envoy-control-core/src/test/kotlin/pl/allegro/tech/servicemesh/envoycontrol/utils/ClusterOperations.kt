package pl.allegro.tech.servicemesh.envoycontrol.utils

import com.google.protobuf.Duration
import com.google.protobuf.util.Durations
import io.envoyproxy.envoy.config.cluster.v3.Cluster
import io.envoyproxy.envoy.config.core.v3.AggregatedConfigSource
import io.envoyproxy.envoy.config.core.v3.ConfigSource
import io.envoyproxy.envoy.config.core.v3.HttpProtocolOptions
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.ClusterConfiguration
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotProperties
import pl.allegro.tech.servicemesh.envoycontrol.utils.TestData.CLUSTER_NAME

fun createCluster(
    defaultProperties: SnapshotProperties = SnapshotProperties(),
    clusterName: String = CLUSTER_NAME,
    idleTimeout: Long = TestData.DEFAULT_IDLE_TIMEOUT
): Cluster {
    return Cluster.newBuilder().setName(clusterName)
        .setType(Cluster.DiscoveryType.EDS)
        .setConnectTimeout(Durations.fromMillis(defaultProperties.edsConnectionTimeout.toMillis()))
        .setEdsClusterConfig(
            Cluster.EdsClusterConfig.newBuilder().setEdsConfig(
                ConfigSource.newBuilder().setAds(AggregatedConfigSource.newBuilder())
            )
        )
        .setLbPolicy(defaultProperties.loadBalancing.policy)
        .setCommonHttpProtocolOptions(
            HttpProtocolOptions.newBuilder()
                .setIdleTimeout(Duration.newBuilder().setSeconds(idleTimeout).build())
        )
        .build()
}

fun createClusterConfigurations(vararg clusters: Cluster): Map<String, ClusterConfiguration> {
    return clusters.associate { it.name to ClusterConfiguration(it.name, false) }
}
