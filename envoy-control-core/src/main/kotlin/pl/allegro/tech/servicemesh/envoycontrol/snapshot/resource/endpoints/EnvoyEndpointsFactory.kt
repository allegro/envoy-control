package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.endpoints

import com.google.protobuf.ListValue
import com.google.protobuf.Struct
import com.google.protobuf.UInt32Value
import com.google.protobuf.Value
import io.envoyproxy.envoy.config.core.v3.Address
import io.envoyproxy.envoy.config.core.v3.Locality
import io.envoyproxy.envoy.config.core.v3.Metadata
import io.envoyproxy.envoy.config.core.v3.SocketAddress
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment
import io.envoyproxy.envoy.config.endpoint.v3.Endpoint
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint
import io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints
import pl.allegro.tech.servicemesh.envoycontrol.groups.RoutingPolicy
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
                    .map {
                        val locality = it.locality
                        val cluster = it.cluster

                        createEndpointsGroup(it.servicesState[serviceName], cluster, locality)
                    }

                ClusterLoadAssignment.newBuilder()
                    .setClusterName(serviceName)
                    .addAllEndpoints(localityLbEndpoints)
                    .build()
            }
    }

    fun filterEndpoints(
        clusterLoadAssignment: ClusterLoadAssignment,
        routingPolicy: RoutingPolicy
    ): ClusterLoadAssignment {
        if (!routingPolicy.autoServiceTag || !properties.routing.serviceTags.enabled) {
            return clusterLoadAssignment
        }

        val filteredLoadAssignment = routingPolicy.serviceTagPreference.firstNotNullOfOrNull { serviceTag ->
            when (containsEndpointWithServiceTag(clusterLoadAssignment, serviceTag)) {
                ContainsResult.ALL -> clusterLoadAssignment
                ContainsResult.SOME -> createFilteredLoadAssignment(clusterLoadAssignment, serviceTag)
                ContainsResult.NONE -> null
            }
        }

        return when {
            filteredLoadAssignment != null -> filteredLoadAssignment
            routingPolicy.fallbackToAnyInstance -> clusterLoadAssignment
            else -> createEmptyLoadAssignment(clusterLoadAssignment)
        }
    }

    private fun createFilteredLoadAssignment(
        loadAssignment: ClusterLoadAssignment,
        serviceTag: String
    ): ClusterLoadAssignment {
        val builder = loadAssignment.toBuilder()
        builder.endpointsBuilderList.forEach { localityLbEndpointsBuilder ->
            val lbEndpointsList = localityLbEndpointsBuilder.lbEndpointsList
            val filteredEndpoints = lbEndpointsList.filter { metadataContainsServiceTag(it.metadata, serviceTag) }
            if (filteredEndpoints.size < lbEndpointsList.size) {
                localityLbEndpointsBuilder.clearLbEndpoints().addAllLbEndpoints(filteredEndpoints)
            }
        }
        return builder.build()
    }

    private fun createEmptyLoadAssignment(loadAssignment: ClusterLoadAssignment): ClusterLoadAssignment {
        return loadAssignment.toBuilder().clearEndpoints().build()
    }

    private enum class ContainsResult {
        ALL, SOME, NONE
    }
    private fun containsEndpointWithServiceTag(loadAssignment: ClusterLoadAssignment, tag: String): ContainsResult {
        var endpointWithTagFound = false
        var endpointWithoutTagFound = false
        loadAssignment.endpointsList.forEach { localityLbEndpint ->
            localityLbEndpint.lbEndpointsList.forEach { lbEndpoint ->
                if (metadataContainsServiceTag(lbEndpoint.metadata, tag)) {
                    endpointWithTagFound = true
                } else {
                    endpointWithoutTagFound = true
                }

                if (endpointWithTagFound && endpointWithoutTagFound) {
                    return ContainsResult.SOME
                }
            }
        }
        return when {
            endpointWithTagFound -> ContainsResult.ALL
            else -> ContainsResult.NONE
        }
    }

    private fun metadataContainsServiceTag(metadata: Metadata, serviceTag: String) = metadata
        .filterMetadataMap["envoy.lb"]?.fieldsMap
        ?.get(properties.routing.serviceTags.metadataKey)
        ?.listValue?.valuesList.orEmpty()
        .any { it.stringValue == serviceTag }

    private fun createEndpointsGroup(
        serviceInstances: ServiceInstances?,
        zone: String,
        locality: pl.allegro.tech.servicemesh.envoycontrol.services.Locality
    ): LocalityLbEndpoints =
        LocalityLbEndpoints.newBuilder()
            .setLocality(Locality.newBuilder().setZone(zone).build())
            .addAllLbEndpoints(serviceInstances?.instances?.map {
                createLbEndpoint(it, serviceInstances.serviceName, locality)
            } ?: emptyList())
            .setPriority(toEnvoyPriority(locality))
            .build()

    private fun createLbEndpoint(
        serviceInstance: ServiceInstance,
        serviceName: String,
        locality: pl.allegro.tech.servicemesh.envoycontrol.services.Locality
    ): LbEndpoint {
        return LbEndpoint.newBuilder()
            .setEndpoint(
                buildEndpoint(serviceInstance)
            )
            .setMetadata(serviceInstance, serviceName, locality)
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
            .setPortValue(serviceInstance.port ?: 0)
            .setProtocol(SocketAddress.Protocol.TCP)
    }

    private fun LbEndpoint.Builder.setMetadata(
        instance: ServiceInstance,
        serviceName: String,
        locality: pl.allegro.tech.servicemesh.envoycontrol.services.Locality
    ): LbEndpoint.Builder {
        val lbMetadataKeys = Struct.newBuilder()
        val socketMatchMetadataKeys = Struct.newBuilder()

        if (properties.loadBalancing.canary.enabled && instance.canary) {
            lbMetadataKeys.putFields(
                properties.loadBalancing.canary.metadataKey,
                Value.newBuilder().setStringValue(properties.loadBalancing.canary.headerValue).build()
            )
        }
        if (instance.regular) {
            lbMetadataKeys.putFields(
                properties.loadBalancing.regularMetadataKey,
                Value.newBuilder().setBoolValue(true).build()
            )
        }
        if (properties.routing.serviceTags.enabled) {
            addServiceTagsToMetadata(lbMetadataKeys, instance, serviceName)
        }
        if (instance.tags.contains(properties.incomingPermissions.tlsAuthentication.mtlsEnabledTag)) {
            socketMatchMetadataKeys.putFields(
                    properties.incomingPermissions.tlsAuthentication.tlsContextMetadataMatchKey,
                    Value.newBuilder().setBoolValue(true).build()
            )
        }
        lbMetadataKeys.putFields(
            properties.loadBalancing.localityMetadataKey,
            Value.newBuilder().setStringValue(locality.name).build()
        )
        return setMetadata(Metadata.newBuilder()
                .putFilterMetadata("envoy.lb", lbMetadataKeys.build())
                .putFilterMetadata("envoy.transport_socket_match", socketMatchMetadataKeys.build())
        )
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
