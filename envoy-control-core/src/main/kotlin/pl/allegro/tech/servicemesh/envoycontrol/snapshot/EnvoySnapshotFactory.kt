package pl.allegro.tech.servicemesh.envoycontrol.snapshot

import com.google.protobuf.ListValue
import com.google.protobuf.Struct
import com.google.protobuf.UInt32Value
import com.google.protobuf.Value
import com.google.protobuf.util.Durations
import io.envoyproxy.controlplane.cache.Snapshot
import io.envoyproxy.envoy.api.v2.Cluster
import io.envoyproxy.envoy.api.v2.ClusterLoadAssignment
import io.envoyproxy.envoy.api.v2.Listener
import io.envoyproxy.envoy.api.v2.RouteConfiguration
import io.envoyproxy.envoy.api.v2.auth.Secret
import io.envoyproxy.envoy.api.v2.core.Address
import io.envoyproxy.envoy.api.v2.core.Locality
import io.envoyproxy.envoy.api.v2.core.Metadata
import io.envoyproxy.envoy.api.v2.core.SocketAddress
import io.envoyproxy.envoy.api.v2.endpoint.Endpoint
import io.envoyproxy.envoy.api.v2.endpoint.LbEndpoint
import io.envoyproxy.envoy.api.v2.endpoint.LocalityLbEndpoints
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import pl.allegro.tech.servicemesh.envoycontrol.groups.AllServicesGroup
import pl.allegro.tech.servicemesh.envoycontrol.groups.DependencySettings
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group
import pl.allegro.tech.servicemesh.envoycontrol.groups.Outgoing
import pl.allegro.tech.servicemesh.envoycontrol.groups.ProxySettings
import pl.allegro.tech.servicemesh.envoycontrol.groups.ServicesGroup
import pl.allegro.tech.servicemesh.envoycontrol.services.LocalityAwareServicesState
import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceInstance
import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceInstances
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.listeners.EnvoyListenersFactory
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.routing.ServiceTagMetadataGenerator
import pl.allegro.tech.servicemesh.envoycontrol.services.Locality as LocalityEnum

internal class EnvoySnapshotFactory(
    private val ingressRoutesFactory: EnvoyIngressRoutesFactory,
    private val egressRoutesFactory: EnvoyEgressRoutesFactory,
    private val clustersFactory: EnvoyClustersFactory,
    private val listenersFactory: EnvoyListenersFactory,
    private val snapshotsVersions: SnapshotsVersions,
    private val properties: SnapshotProperties,
    private val serviceTagFilter: ServiceTagMetadataGenerator = ServiceTagMetadataGenerator(
            properties.routing.serviceTags
    ),
    private val defaultDependencySettings: DependencySettings =
DependencySettings(
    handleInternalRedirect = properties.egress.handleInternalRedirect,
    timeoutPolicy = Outgoing.TimeoutPolicy(
        idleTimeout = Durations.fromMillis(properties.egress.commonHttp.idleTimeout.toMillis()),
        requestTimeout = Durations.fromMillis(properties.egress.commonHttp.requestTimeout.toMillis())
    )
),
    private val meterRegistry: MeterRegistry
) {
    fun newSnapshot(servicesStates: List<LocalityAwareServicesState>, ads: Boolean): Snapshot {
        val sample = Timer.start(meterRegistry)

        val serviceNames = servicesStates.flatMap { it.servicesState.serviceNames() }.distinct()

        val clusterConfigurations = if (properties.egress.http2.enabled) {
            servicesStates.flatMap {
                it.servicesState.serviceNameToInstances.values
            }.groupBy {
                it.serviceName
            }.map { (serviceName, instances) ->
                toClusterConfiguration(instances, serviceName)
            }
        } else {
            serviceNames.map { ClusterConfiguration(serviceName = it, http2Enabled = false) }
        }

        val clusters = clustersFactory.getClustersForServices(clusterConfigurations, ads)

        val endpoints: List<ClusterLoadAssignment> = createLoadAssignment(servicesStates)
        val routes = listOf(
            egressRoutesFactory.createEgressRouteConfig("", serviceNames.map {
                RouteSpecification(
                    clusterName = it,
                    routeDomain = it,
                    settings = defaultDependencySettings
                )
            }),
            ingressRoutesFactory.createSecuredIngressRouteConfig(ProxySettings())
        )

        val version = snapshotsVersions.version(AllServicesGroup(ads, listenersConfig = null), clusters, endpoints)

        val snapshot = createSnapshot(
            clusters = clusters,
            clustersVersion = version.clusters,
            endpoints = endpoints,
            endpointsVersions = version.endpoints,
            routes = routes,
            routesVersion = RoutesVersion(version.clusters.value)
        )
        sample.stop(meterRegistry.timer("snapshot-factory.new-snapshot.time"))

        return snapshot
    }

    private fun toClusterConfiguration(instances: List<ServiceInstances>, serviceName: String): ClusterConfiguration {
        val allInstances = instances.flatMap {
            it.instances
        }

        // Http2 support is on a cluster level so if someone decides to deploy a service in dc1 with envoy and in dc2
        // without envoy then we can't set http2 because we do not know if the server in dc2 supports it.
        val allInstancesHaveEnvoyTag = allInstances.isNotEmpty() && allInstances.all {
            it.tags.contains(properties.egress.http2.tagName)
        }

        return ClusterConfiguration(serviceName, allInstancesHaveEnvoyTag)
    }

    fun getSnapshotForGroup(group: Group, globalSnapshot: Snapshot): Snapshot {
        val groupSample = Timer.start(meterRegistry)
        if (group.isGlobalGroup()) {
            return globalSnapshot
        }

        val newSnapshotForGroup = newSnapshotForGroup(group, globalSnapshot)
        groupSample.stop(meterRegistry.timer("snapshot-factory.get-snapshot-for-group.time"))
        return newSnapshotForGroup
    }

    private fun getEgressRoutesSpecification(group: Group, globalSnapshot: Snapshot): Collection<RouteSpecification> {
        return getServiceRouteSpecifications(group, globalSnapshot) +
            getDomainRouteSpecifications(group)
    }

    private fun getDomainRouteSpecifications(group: Group): List<RouteSpecification> {
        return group.proxySettings.outgoing.getDomainDependencies().map {
            RouteSpecification(
                clusterName = it.getClusterName(),
                routeDomain = it.getRouteDomain(),
                settings = it.settings
            )
        }
    }

    private fun getServiceRouteSpecifications(group: Group, globalSnapshot: Snapshot): Collection<RouteSpecification> {
        return when (group) {
            is ServicesGroup -> group.proxySettings.outgoing.getServiceDependencies().map {
                RouteSpecification(
                    clusterName = it.service,
                    routeDomain = it.service,
                    settings = it.settings
                )
            }
            is AllServicesGroup -> globalSnapshot.clusters().resources().map {
                RouteSpecification(
                    clusterName = it.key,
                    routeDomain = it.key,
                    settings = defaultDependencySettings
                )
            }
        }
    }

    private fun getServicesEndpointsForGroup(group: Group, globalSnapshot: Snapshot): List<ClusterLoadAssignment> {
        return getServiceRouteSpecifications(group, globalSnapshot)
            .mapNotNull { globalSnapshot.endpoints().resources().get(it.clusterName) }
    }

    private fun newSnapshotForGroup(
        group: Group,
        globalSnapshot: Snapshot
    ): Snapshot {

        val clusters: List<Cluster> =
            clustersFactory.getClustersForGroup(group, globalSnapshot)

        val routes = listOf(
            egressRoutesFactory.createEgressRouteConfig(
                group.serviceName, getEgressRoutesSpecification(group, globalSnapshot)
            ),
            ingressRoutesFactory.createSecuredIngressRouteConfig(group.proxySettings)
        )

        if (clusters.isEmpty()) {
            return createSnapshot(routes = routes)
        }

        val endpoints = getServicesEndpointsForGroup(group, globalSnapshot)

        val version = snapshotsVersions.version(group, clusters, endpoints)

        val listeners = if (properties.dynamicListeners.enabled) {
            listenersFactory.createListeners(group)
        } else {
            emptyList()
        }

        return createSnapshot(
                clusters = clusters,
                clustersVersion = version.clusters,
                endpoints = endpoints,
                endpointsVersions = version.endpoints,
                listeners = listeners,
                // for now we assume that listeners don't change during lifecycle
                routes = routes,
                // we assume, that routes don't change during Envoy lifecycle unless clusters change
                routesVersion = RoutesVersion(version.clusters.value)
        )
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

    private fun toEnvoyPriority(locality: LocalityEnum): Int = if (locality == LocalityEnum.LOCAL) 0 else 1

    private fun createLoadAssignment(
        localityAwareServicesStates: List<LocalityAwareServicesState>
    ): List<ClusterLoadAssignment> {
        return localityAwareServicesStates
            .flatMap {
                val locality = it.locality
                val zone = it.zone

                it.servicesState.serviceNameToInstances.map { (serviceName, serviceInstances) ->
                    serviceName to createEndpointsGroup(serviceInstances, zone, toEnvoyPriority(locality))
                }
            }
            .groupBy { (serviceName) ->
                serviceName
            }
            .map { (serviceName, serviceNameLocalityLbEndpointsPairs) ->
                val localityLbEndpoints = serviceNameLocalityLbEndpointsPairs.map { (_, localityLbEndpoint) ->
                    localityLbEndpoint
                }

                ClusterLoadAssignment.newBuilder()
                    .setClusterName(serviceName)
                    .addAllEndpoints(localityLbEndpoints)
                    .build()
            }
    }

    private fun createSnapshot(
        clusters: List<Cluster> = emptyList(),
        clustersVersion: ClustersVersion = ClustersVersion.EMPTY_VERSION,
        endpoints: List<ClusterLoadAssignment> = emptyList(),
        endpointsVersions: EndpointsVersion = EndpointsVersion.EMPTY_VERSION,
        routes: List<RouteConfiguration> = emptyList(),
        routesVersion: RoutesVersion = RoutesVersion(properties.routes.initialVersion),
        listeners: List<Listener> = emptyList(),
        listenersVersion: ListenersVersion = ListenersVersion(properties.dynamicListeners.initialVersion)
    ): Snapshot =
        Snapshot.create(
            clusters,
            clustersVersion.value,
            endpoints,
            endpointsVersions.value,
            listeners,
            listenersVersion.value,
            routes,
            routesVersion.value,
            emptyList<Secret>(),
            SecretsVersion.EMPTY_VERSION.value
        )

    internal data class ClusterConfiguration(val serviceName: String, val http2Enabled: Boolean)
}

class RouteSpecification(
    val clusterName: String,
    val routeDomain: String,
    val settings: DependencySettings
)
