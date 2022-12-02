package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource

import com.google.protobuf.Duration
import com.google.protobuf.util.Durations
import io.envoyproxy.envoy.config.cluster.v3.Cluster
import io.envoyproxy.envoy.config.core.v3.AggregatedConfigSource
import io.envoyproxy.envoy.config.core.v3.ConfigSource
import io.envoyproxy.envoy.config.core.v3.HttpProtocolOptions
import pl.allegro.tech.servicemesh.envoycontrol.groups.DependencySettings
import pl.allegro.tech.servicemesh.envoycontrol.groups.Outgoing
import pl.allegro.tech.servicemesh.envoycontrol.groups.ServiceDependency
import pl.allegro.tech.servicemesh.envoycontrol.groups.TagDependency
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.clusters.EnvoyClusterFactoryTest

fun createClusters(
    properties: SnapshotProperties,
    serviceNames: List<String>
): Map<String, Cluster> {
    return serviceNames.map {
        createCluster(properties, it)
    }.associateBy { it.name }
}

fun createCluster(
    defaultProperties: SnapshotProperties,
    serviceName: String,
): Cluster {
    return Cluster.newBuilder().setName(serviceName)
        .setType(Cluster.DiscoveryType.EDS)
        .setConnectTimeout(Durations.fromMillis(defaultProperties.edsConnectionTimeout.toMillis()))
        .setEdsClusterConfig(
            Cluster.EdsClusterConfig.newBuilder().setEdsConfig(
                ConfigSource.newBuilder().setAds(AggregatedConfigSource.newBuilder())
            ).setServiceName(serviceName)
        )
        .setLbPolicy(defaultProperties.loadBalancing.policy)
        .setCommonHttpProtocolOptions(
            HttpProtocolOptions.newBuilder()
                .setIdleTimeout(Duration.newBuilder().setSeconds(100).build())
        )
        .build()
}

fun serviceDependency(name: String, idleTimeout: Long) = ServiceDependency(
    name,
    settings = DependencySettings(
        timeoutPolicy = Outgoing.TimeoutPolicy(
            connectionIdleTimeout = Duration.newBuilder().setSeconds(idleTimeout).build()
        )
    )
)

fun tagDependency(name: String, idleTimeout: Long) = TagDependency(
    name,
    settings = DependencySettings(
        timeoutPolicy = Outgoing.TimeoutPolicy(
            connectionIdleTimeout = Duration.newBuilder().setSeconds(idleTimeout).build()
        )
    )
)
