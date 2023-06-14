package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.endpoints

import com.google.protobuf.ListValue
import com.google.protobuf.Struct
import com.google.protobuf.UInt32Value
import com.google.protobuf.Value
import io.envoyproxy.envoy.config.core.v3.Address
import io.envoyproxy.envoy.config.core.v3.Metadata
import io.envoyproxy.envoy.config.core.v3.SocketAddress
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment
import io.envoyproxy.envoy.config.endpoint.v3.Endpoint
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint
import io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints
import pl.allegro.tech.servicemesh.envoycontrol.groups.RoutingPolicy
import pl.allegro.tech.servicemesh.envoycontrol.logger
import pl.allegro.tech.servicemesh.envoycontrol.services.Locality
import pl.allegro.tech.servicemesh.envoycontrol.services.MultiClusterState
import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceInstance
import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceInstances
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.routes.ServiceTagMetadataGenerator

typealias EnvoyProxyLocality = io.envoyproxy.envoy.config.core.v3.Locality

class EnvoyEndpointsFactory(
    private val properties: SnapshotProperties,
    private val serviceTagFilter: ServiceTagMetadataGenerator = ServiceTagMetadataGenerator(
        properties.routing.serviceTags
    ),
    private val currentZone: String
) {
    companion object {
        private val logger by logger()
    }

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
        if (!routingPolicy.autoServiceTag || !properties.routing.serviceTags.isAutoServiceTagEffectivelyEnabled()) {
            return clusterLoadAssignment
        }

        val filteredLoadAssignment = routingPolicy.serviceTagPreference.firstNotNullOfOrNull { serviceTag ->
            filterEndpoints(clusterLoadAssignment, serviceTag)
        }

        return when {
            filteredLoadAssignment != null -> filteredLoadAssignment
            routingPolicy.fallbackToAnyInstance -> clusterLoadAssignment
            else -> createEmptyLoadAssignment(clusterLoadAssignment)
        }
    }

    private fun filterEndpoints(loadAssignment: ClusterLoadAssignment, tag: String): ClusterLoadAssignment? {
        var allEndpointMatched = true
        val filteredEndpoints = loadAssignment.endpointsList.mapNotNull { localityLbEndpoint ->
            val (matchedEndpoints, unmatchedEndpoints) = localityLbEndpoint.lbEndpointsList.partition {
                metadataContainsServiceTag(it.metadata, tag)
            }
            when {
                matchedEndpoints.isNotEmpty() && unmatchedEndpoints.isNotEmpty() -> { // SOME
                    allEndpointMatched = false
                    localityLbEndpoint.toBuilder()
                        .clearLbEndpoints()
                        .addAllLbEndpoints(matchedEndpoints)
                        .build()
                }

                matchedEndpoints.isNotEmpty() -> { // ALL
                    localityLbEndpoint
                }

                else -> { // NONE
                    allEndpointMatched = false
                    null
                }
            }
        }
        return when {
            allEndpointMatched -> loadAssignment // ALL
            filteredEndpoints.isNotEmpty() -> loadAssignment.toBuilder() // SOME
                .clearEndpoints()
                .addAllEndpoints(filteredEndpoints)
                .build()

            else -> null // NONE
        }
    }

    private fun createEmptyLoadAssignment(loadAssignment: ClusterLoadAssignment): ClusterLoadAssignment {
        return loadAssignment.toBuilder().clearEndpoints().build()
    }

    private fun metadataContainsServiceTag(metadata: Metadata, serviceTag: String) = metadata
        .filterMetadataMap["envoy.lb"]?.fieldsMap
        ?.get(properties.routing.serviceTags.metadataKey)
        ?.listValue?.valuesList.orEmpty()
        .any { it.stringValue == serviceTag }

    private fun createEndpointsGroup(
        serviceInstances: ServiceInstances?,
        zone: String,
        locality: Locality
    ): LocalityLbEndpoints =
        LocalityLbEndpoints.newBuilder()
            .setLocality(EnvoyProxyLocality.newBuilder().setZone(zone).build())
            .addAllLbEndpoints(serviceInstances?.instances?.map {
                createLbEndpoint(it, serviceInstances.serviceName, locality)
            } ?: emptyList())
            .setPriority(toEnvoyPriority(zone, locality))
            .build()

    private fun createLbEndpoint(
        serviceInstance: ServiceInstance,
        serviceName: String,
        locality: Locality
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
        locality: Locality
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
        return setMetadata(
            Metadata.newBuilder()
                .putFilterMetadata("envoy.lb", lbMetadataKeys.build())
                .putFilterMetadata("envoy.transport_socket_match", socketMatchMetadataKeys.build())
        )
    }

    private fun addServiceTagsToMetadata(metadata: Struct.Builder, instance: ServiceInstance, serviceName: String) {
        serviceTagFilter.getAllTagsForRouting(serviceName, instance.tags)?.let { tags ->
            metadata.putFields(
                properties.routing.serviceTags.metadataKey,
                Value.newBuilder()
                    .setListValue(
                        ListValue.newBuilder()
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

    private fun toEnvoyPriority(zone: String, locality: Locality): Int {
        val zonePriorities = properties.loadBalancing.priorities.zonePriorities
        return when (zonePriorities.isNotEmpty()) {
            true -> zonePriorities[currentZone]?.get(zone) ?: toEnvoyPriority(locality)
            false -> toEnvoyPriority(locality)
        }.also {
            logger.debug(
                "Resolved lb priority to {} with zone={}, currentZone={}, priority props={}",
                it, zone, currentZone, zonePriorities
            )
        }
    }

    private fun toEnvoyPriority(locality: Locality): Int = if (locality == Locality.LOCAL) 0 else 1
}
