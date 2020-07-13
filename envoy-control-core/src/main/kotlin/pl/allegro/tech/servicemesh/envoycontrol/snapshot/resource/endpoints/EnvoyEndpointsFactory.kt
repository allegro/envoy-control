package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.endpoints

import com.google.protobuf.ListValue
import com.google.protobuf.Struct
import com.google.protobuf.UInt32Value
import com.google.protobuf.Value
import io.envoyproxy.envoy.api.v2.ClusterLoadAssignment
import io.envoyproxy.envoy.api.v2.core.Address
import io.envoyproxy.envoy.api.v2.core.Locality
import io.envoyproxy.envoy.api.v2.core.Metadata
import io.envoyproxy.envoy.api.v2.core.SocketAddress
import io.envoyproxy.envoy.api.v2.endpoint.Endpoint
import io.envoyproxy.envoy.api.v2.endpoint.LbEndpoint
import io.envoyproxy.envoy.api.v2.endpoint.LocalityLbEndpoints
import pl.allegro.tech.servicemesh.envoycontrol.services.MultiClusterState
import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceInstance
import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceInstances
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.routes.ServiceTagMetadataGenerator

class EnvoyEndpointsFactory(
    private val properties: SnapshotProperties,
    private val serviceTagFilter: ServiceTagMetadataGenerator = ServiceTagMetadataGenerator(
        properties.routing.serviceTags
    )
) {

    fun createLoadAssignment(
        clusters: Set<String>,
        multiClusterState: MultiClusterState
    ): List<ClusterLoadAssignment> {

        return clusters
            .map { serviceName ->
                val localityLbEndpoints = multiClusterState
                    .mapNotNull {
                        val locality = it.locality
                        val cluster = it.cluster

                        it.servicesState.get(serviceName)?.let {
                            createEndpointsGroup(it, cluster, toEnvoyPriority(locality))
                        }
                    }

                ClusterLoadAssignment.newBuilder()
                    .setClusterName(serviceName)
                    .addAllEndpoints(localityLbEndpoints)
                    .build()
            }
    }

    private fun createEndpointsGroup(
        serviceInstances: ServiceInstances,
        zone: String,
        priority: Int
    ): LocalityLbEndpoints =
        LocalityLbEndpoints.newBuilder()
            .setLocality(Locality.newBuilder().setZone(zone).build())
            .addAllLbEndpoints(serviceInstances.instances.map { createLbEndpoint(it, serviceInstances.serviceName) })
            .setPriority(priority)
            .build()

    private fun createLbEndpoint(serviceInstance: ServiceInstance, serviceName: String): LbEndpoint {
        return LbEndpoint.newBuilder()
            .setEndpoint(
                buildEndpoint(serviceInstance)
            )
            .setMetadata(serviceInstance, serviceName)
            .setLoadBalancingWeightFromInstance(serviceInstance)
            .build()
    }

    private fun buildEndpoint(serviceInstance: ServiceInstance): Endpoint.Builder {
        return Endpoint.newBuilder()
            .setAddress(
                buildAddress(serviceInstance)
            )
    }

    private fun buildAddress(serviceInstance: ServiceInstance): Address.Builder {
        return Address.newBuilder()
            .setSocketAddress(
                buildSocketAddress(serviceInstance)
            )
    }

    private fun buildSocketAddress(serviceInstance: ServiceInstance): SocketAddress.Builder {
        return SocketAddress.newBuilder()
            .setAddress(serviceInstance.address)
            .setPortValue(serviceInstance.port)
            .setProtocol(SocketAddress.Protocol.TCP)
    }

    private fun LbEndpoint.Builder.setMetadata(instance: ServiceInstance, serviceName: String): LbEndpoint.Builder {
        val metadataKeys = Struct.newBuilder()

        if (properties.loadBalancing.canary.enabled && instance.canary) {
            metadataKeys.putFields(
                properties.loadBalancing.canary.metadataKey,
                Value.newBuilder().setStringValue(properties.loadBalancing.canary.headerValue).build()
            )
        }
        if (instance.regular) {
            metadataKeys.putFields(
                properties.loadBalancing.regularMetadataKey,
                Value.newBuilder().setBoolValue(true).build()
            )
        }

        if (properties.routing.serviceTags.enabled) {
            addServiceTagsToMetadata(metadataKeys, instance, serviceName)
        }

        return setMetadata(Metadata.newBuilder().putFilterMetadata("envoy.lb", metadataKeys.build()))
    }

    private fun addServiceTagsToMetadata(metadata: Struct.Builder, instance: ServiceInstance, serviceName: String) {
        serviceTagFilter.getAllTagsForRouting(serviceName, instance.tags)?.let { tags ->
            metadata.putFields(
                properties.routing.serviceTags.metadataKey,
                Value.newBuilder()
                    .setListValue(ListValue.newBuilder()
                        .addAllValues(tags.map { Value.newBuilder().setStringValue(it).build() }.asIterable())
                    ).build()
            )
        }
    }

    private fun LbEndpoint.Builder.setLoadBalancingWeightFromInstance(instance: ServiceInstance): LbEndpoint.Builder =
        when (properties.loadBalancing.weights.enabled) {
            true -> setLoadBalancingWeight(UInt32Value.of(instance.weight))
            false -> this
        }

    private fun toEnvoyPriority(locality: pl.allegro.tech.servicemesh.envoycontrol.services.Locality): Int =
        if (locality == pl.allegro.tech.servicemesh.envoycontrol.services.Locality.LOCAL) 0 else 1
}
