package pl.allegro.tech.servicemesh.envoycontrol.snapshot

import io.envoyproxy.controlplane.cache.Snapshot
import io.envoyproxy.envoy.api.v2.Cluster
import io.envoyproxy.envoy.api.v2.ClusterLoadAssignment
import io.envoyproxy.envoy.api.v2.Listener
import io.envoyproxy.envoy.api.v2.RouteConfiguration
import io.envoyproxy.envoy.api.v2.auth.Secret
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import pl.allegro.tech.servicemesh.envoycontrol.groups.AllServicesGroup
import pl.allegro.tech.servicemesh.envoycontrol.groups.CommunicationMode
import pl.allegro.tech.servicemesh.envoycontrol.groups.DependencySettings
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group
import pl.allegro.tech.servicemesh.envoycontrol.groups.ServicesGroup
import pl.allegro.tech.servicemesh.envoycontrol.services.MultiClusterState
import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceInstance
import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceInstances
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.clusters.EnvoyClustersFactory
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.endpoints.EnvoyEndpointsFactory
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.EnvoyListenersFactory
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.routes.EnvoyEgressRoutesFactory
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.routes.EnvoyIngressRoutesFactory

class EnvoySnapshotFactory(
    private val ingressRoutesFactory: EnvoyIngressRoutesFactory,
    private val egressRoutesFactory: EnvoyEgressRoutesFactory,
    private val clustersFactory: EnvoyClustersFactory,
    private val endpointsFactory: EnvoyEndpointsFactory,
    private val listenersFactory: EnvoyListenersFactory,
    private val snapshotsVersions: SnapshotsVersions,
    private val properties: SnapshotProperties,
    private val meterRegistry: MeterRegistry
) {
    fun newSnapshot(
        servicesStates: MultiClusterState,
        clusterConfigurations: Map<String, ClusterConfiguration>,
        communicationMode: CommunicationMode
    ): GlobalSnapshot {
        val sample = Timer.start(meterRegistry)

        val clusters = clustersFactory.getClustersForServices(clusterConfigurations.values, communicationMode)
        val securedClusters = clustersFactory.getSecuredClusters(clusters)

        val endpoints: List<ClusterLoadAssignment> = endpointsFactory.createLoadAssignment(
            clusters = clusterConfigurations.keys,
            multiClusterState = servicesStates
        )

        val snapshot = globalSnapshot(
            clusterConfigurations = clusterConfigurations,
            clusters = clusters,
            securedClusters = securedClusters,
            endpoints = endpoints,
            properties = properties.outgoingPermissions
        )
        sample.stop(meterRegistry.timer("snapshot-factory.new-snapshot.time"))

        return snapshot
    }

    fun clusterConfigurations(
        servicesStates: MultiClusterState,
        previousClusters: Map<String, ClusterConfiguration>
    ): Map<String, ClusterConfiguration> {
        val currentClusters = if (properties.egress.http2.enabled) {
            servicesStates.flatMap {
                it.servicesState.serviceNameToInstances.values
            }.groupBy {
                it.serviceName
            }.mapValues { (serviceName, instances) ->
                toClusterConfiguration(instances, serviceName, previousClusters[serviceName])
            }
        } else {
            servicesStates
                .flatMap { it.servicesState.serviceNames() }
                .distinct()
                .associateWith { ClusterConfiguration(serviceName = it, http2Enabled = false) }
        }

        return addRemovedClusters(previousClusters, currentClusters)
    }

    private fun addRemovedClusters(
        previous: Map<String, ClusterConfiguration>,
        current: Map<String, ClusterConfiguration>
    ): Map<String, ClusterConfiguration> {

        val shouldKeepRemoved = if (properties.egress.neverRemoveClusters) {
            previous.keys.any { it !in current }
        } else {
            false
        }

        return when (shouldKeepRemoved) {
            true -> {
                val removedClusters = previous - current.keys
                current + removedClusters
            }
            false -> current
        }
    }

    private fun toClusterConfiguration(
        instances: List<ServiceInstances>,
        serviceName: String,
        previousCluster: ClusterConfiguration?
    ): ClusterConfiguration {
        val allInstances = instances.flatMap {
            it.instances
        }
        val http2EnabledTag = properties.egress.http2.tagName

        // Http2 support is on a cluster level so if someone decides to deploy a service in dc1 with envoy and in dc2
        // without envoy then we can't set http2 because we do not know if the server in dc2 supports it.
        val http2Enabled = enableFeatureForClustersWithTag(allInstances, previousCluster?.http2Enabled, http2EnabledTag)

        return ClusterConfiguration(serviceName, http2Enabled)
    }

    private fun enableFeatureForClustersWithTag(
        allInstances: List<ServiceInstance>,
        previousValue: Boolean?,
        tag: String
    ): Boolean {
        val allInstancesHaveTag = allInstances.isNotEmpty() && allInstances.all {
            it.tags.contains(tag)
        }

        return when {
            allInstances.isEmpty() -> previousValue ?: false
            allInstancesHaveTag -> true
            else -> false
        }
    }

    fun getSnapshotForGroup(group: Group, globalSnapshot: GlobalSnapshot): Snapshot {
        val groupSample = Timer.start(meterRegistry)

        val newSnapshotForGroup = newSnapshotForGroup(group, globalSnapshot)
        groupSample.stop(meterRegistry.timer("snapshot-factory.get-snapshot-for-group.time"))
        return newSnapshotForGroup
    }

    private fun getEgressRoutesSpecification(
        group: Group,
        globalSnapshot: GlobalSnapshot
    ): Collection<RouteSpecification> {
        return getServiceRouteSpecifications(group, globalSnapshot) +
            getDomainRouteSpecifications(group)
    }

    private fun getDomainRouteSpecifications(group: Group): List<RouteSpecification> {
        return group.proxySettings.outgoing.domainDependencies.map {
            RouteSpecification(
                clusterName = it.getClusterName(),
                routeDomain = it.getRouteDomain(),
                settings = it.settings
            )
        }
    }

    private fun getServiceRouteSpecifications(
        group: Group,
        globalSnapshot: GlobalSnapshot
    ): Collection<RouteSpecification> {
        val definedServicesRoutes = group.proxySettings.outgoing.serviceDependencies.map {
            RouteSpecification(
                clusterName = it.service,
                routeDomain = it.service,
                settings = it.settings
            )
        }
        return when (group) {
            is ServicesGroup -> {
                definedServicesRoutes
            }
            is AllServicesGroup -> {
                val servicesNames = group.proxySettings.outgoing.serviceDependencies.map { it.service }.toSet()
                val allServicesRoutes = globalSnapshot.allServicesNames.subtract(servicesNames).map {
                    RouteSpecification(
                        clusterName = it,
                        routeDomain = it,
                        settings = group.proxySettings.outgoing.defaultServiceSettings
                    )
                }
                allServicesRoutes + definedServicesRoutes
            }
        }
    }

    private fun getServicesEndpointsForGroup(
        globalSnapshot: GlobalSnapshot,
        egressRouteSpecifications: Collection<RouteSpecification>
    ): List<ClusterLoadAssignment> {
        return egressRouteSpecifications
            .mapNotNull { globalSnapshot.endpoints.resources().get(it.clusterName) }
    }

    private fun newSnapshotForGroup(
        group: Group,
        globalSnapshot: GlobalSnapshot
    ): Snapshot {

        // TODO(dj): This is where serious refactoring needs to be done
        val egressRouteSpecification = getEgressRoutesSpecification(group, globalSnapshot)

        val clusters: List<Cluster> =
            clustersFactory.getClustersForGroup(group, globalSnapshot)

        val routes = listOf(
            egressRoutesFactory.createEgressRouteConfig(
                group.serviceName, egressRouteSpecification,
                group.listenersConfig?.addUpstreamExternalAddressHeader ?: false
            ),
            ingressRoutesFactory.createSecuredIngressRouteConfig(group.proxySettings)
        )

        val listeners = if (properties.dynamicListeners.enabled) {
            listenersFactory.createListeners(group, globalSnapshot)
        } else {
            emptyList()
        }

        // TODO(dj): endpoints depends on prerequisite of routes -> but only to extract clusterName,
        // which is present only in services (not domains) so it could be implemented differently.
        val endpoints = getServicesEndpointsForGroup(globalSnapshot, egressRouteSpecification)

        val version = snapshotsVersions.version(group, clusters, endpoints, listeners)

        return createSnapshot(
            clusters = clusters,
            clustersVersion = version.clusters,
            endpoints = endpoints,
            endpointsVersions = version.endpoints,
            listeners = listeners,
            // TODO: java-control-plane: https://github.com/envoyproxy/java-control-plane/issues/134
            listenersVersion = version.listeners,
            routes = routes,
            routesVersion = version.routes
        )
    }

    private fun createSnapshot(
        clusters: List<Cluster> = emptyList(),
        clustersVersion: ClustersVersion = ClustersVersion.EMPTY_VERSION,
        endpoints: List<ClusterLoadAssignment> = emptyList(),
        endpointsVersions: EndpointsVersion = EndpointsVersion.EMPTY_VERSION,
        routes: List<RouteConfiguration> = emptyList(),
        routesVersion: RoutesVersion,
        listeners: List<Listener> = emptyList(),
        listenersVersion: ListenersVersion
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
}

data class ClusterConfiguration(
    val serviceName: String,
    val http2Enabled: Boolean
)

class RouteSpecification(
    val clusterName: String,
    val routeDomain: String,
    val settings: DependencySettings
)
