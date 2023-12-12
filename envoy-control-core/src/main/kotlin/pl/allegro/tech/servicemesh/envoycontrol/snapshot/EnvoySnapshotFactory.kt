package pl.allegro.tech.servicemesh.envoycontrol.snapshot

import io.envoyproxy.controlplane.cache.v3.Snapshot
import io.envoyproxy.envoy.config.cluster.v3.Cluster
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment
import io.envoyproxy.envoy.config.listener.v3.Listener
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.Secret
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import pl.allegro.tech.servicemesh.envoycontrol.groups.AllServicesGroup
import pl.allegro.tech.servicemesh.envoycontrol.groups.CommunicationMode
import pl.allegro.tech.servicemesh.envoycontrol.groups.DependencySettings
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group
import pl.allegro.tech.servicemesh.envoycontrol.groups.IncomingRateLimitEndpoint
import pl.allegro.tech.servicemesh.envoycontrol.groups.ServicesGroup
import pl.allegro.tech.servicemesh.envoycontrol.groups.orDefault
import pl.allegro.tech.servicemesh.envoycontrol.logger
import pl.allegro.tech.servicemesh.envoycontrol.services.MultiClusterState
import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceInstance
import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceInstances
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.clusters.EnvoyClustersFactory
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.endpoints.EnvoyEndpointsFactory
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.EnvoyListenersFactory
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.routes.EnvoyEgressRoutesFactory
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.routes.EnvoyIngressRoutesFactory
import java.util.SortedMap

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

    companion object {
        const val DEFAULT_HTTP_PORT = 80
        private val logger by logger()
    }

    fun newSnapshot(
        servicesStates: MultiClusterState,
        clusterConfigurations: Map<String, ClusterConfiguration>,
        communicationMode: CommunicationMode
    ): GlobalSnapshot {
        val sample = Timer.start(meterRegistry)

        val clusters = clustersFactory.getClustersForServices(
            clusterConfigurations.values,
            communicationMode
        )
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
    ): SortedMap<String, ClusterConfiguration> {
        val currentClusters = if (properties.egress.http2.enabled) {
            servicesStates.flatMap {
                it.servicesState.allInstances()
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

        // Clusters need to be sorted because if clusters are in different order to previous snapshot then CDS version
        // is changed and that causes unnecessary CDS responses.
        return addRemovedClusters(previousClusters, currentClusters)
            .toSortedMap()
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

    private fun getDomainRouteSpecifications(
        group: Group
    ): Map<DomainRoutesGrouper, Collection<RouteSpecification>> {
        return group.proxySettings.outgoing.getDomainDependencies().groupBy(
            { DomainRoutesGrouper(it.getPort(), it.useSsl()) },
            {
                StandardRouteSpecification(
                    clusterName = it.getClusterName(),
                    routeDomains = listOf(it.getRouteDomain()),
                    settings = it.settings,
                )
            }
        )
    }

    private fun getDomainPatternRouteSpecifications(group: Group): RouteSpecification {
        return StandardRouteSpecification(
            clusterName = properties.dynamicForwardProxy.clusterName,
            routeDomains = group.proxySettings.outgoing.getDomainPatternDependencies().map { it.domainPattern },
            settings = group.proxySettings.outgoing.defaultServiceSettings,
        )
    }

    private fun getServiceRouteSpecifications(
        group: Group,
        globalSnapshot: GlobalSnapshot
    ): Collection<RouteSpecification> {
        val definedServicesRoutes = group.proxySettings.outgoing.getServiceDependencies().map {
            buildRouteSpecification(
                clusterName = it.service,
                routeDomains = listOf(it.service) + getServiceWithCustomDomain(it.service),
                settings = it.settings,
                group.serviceName,
                globalSnapshot
            )
        }
        return when (group) {
            is ServicesGroup -> {
                definedServicesRoutes
            }

            is AllServicesGroup -> {
                val servicesNames = group.proxySettings.outgoing.getServiceDependencies().map { it.service }.toSet()
                val allServicesRoutes = globalSnapshot.allServicesNames.subtract(servicesNames).map {
                    buildRouteSpecification(
                        clusterName = it,
                        routeDomains = listOf(it) + getServiceWithCustomDomain(it),
                        settings = group.proxySettings.outgoing.defaultServiceSettings,
                        group.serviceName,
                        globalSnapshot
                    )
                }
                allServicesRoutes + definedServicesRoutes
            }
        }
    }

    private fun buildRouteSpecification(
        clusterName: String,
        routeDomains: List<String>,
        settings: DependencySettings,
        serviceName: String,
        globalSnapshot: GlobalSnapshot,
    ): RouteSpecification {
        val trafficSplitting = properties.loadBalancing.trafficSplitting
        val weights = trafficSplitting.serviceByWeightsProperties[serviceName]
        val enabledForDependency = globalSnapshot.endpoints[clusterName]?.endpointsList
            ?.any { e -> trafficSplitting.zoneName == e.locality.zone && e.lbEndpointsCount > 0 }
            ?: false
        logger.debug(
            "Building route spec, weights: $weights, " +
                "serviceName: $serviceName, outgoing dependency clusterName: $clusterName, " +
                "enabled: $enabledForDependency"
        )
        return if (weights != null && enabledForDependency) {
            logger.debug(
                "Building traffic splitting route spec, weights: $weights, " +
                    "serviceName: $serviceName, clusterName: $clusterName, "
            )
            WeightRouteSpecification(
                clusterName,
                routeDomains,
                settings,
                weights
            )
        } else {
            StandardRouteSpecification(
                clusterName,
                routeDomains,
                settings
            )
        }
    }

    private fun getServiceWithCustomDomain(it: String): List<String> {
        return if (properties.egress.domains.isNotEmpty()) {
            properties.egress.domains.map { domain -> "$it$domain" }
        } else {
            emptyList()
        }
    }

    private fun getServicesEndpointsForGroup(
        rateLimitEndpoints: List<IncomingRateLimitEndpoint>,
        globalSnapshot: GlobalSnapshot,
        egressRouteSpecifications: List<RouteSpecification>
    ): List<ClusterLoadAssignment> {
        val egressLoadAssignments = egressRouteSpecifications.mapNotNull { routeSpec ->
            globalSnapshot.endpoints[routeSpec.clusterName]?.let { endpoints ->
                // TODO: create a cache in GlobalSnapshot where a key is a pair (serviceName, serviceTag) and a value
                //    is ClusterLoadAssignment (simple mutable map should be enough).
                //    endpointsFactory.filterEndpoints() can use this cache to prevent computing the same
                //    ClusterLoadAssignments many times - it may reduce MEM, CPU and latency if some serviceTags are
                //    commonly used
                routeSpec.clusterName to endpointsFactory.filterEndpoints(endpoints, routeSpec.settings.routingPolicy)
            }
        }.toMap()

        val rateLimitClusters =
            if (rateLimitEndpoints.isNotEmpty()) listOf(properties.rateLimit.serviceName) else emptyList()
        val rateLimitLoadAssignments = rateLimitClusters.mapNotNull { name -> globalSnapshot.endpoints[name] }
        val secondaryLoadAssignments = endpointsFactory.getSecondaryClusterEndpoints(
            egressLoadAssignments,
            egressRouteSpecifications
        )
        return egressLoadAssignments.values.toList() + rateLimitLoadAssignments + secondaryLoadAssignments
    }

    private fun newSnapshotForGroup(
        group: Group,
        globalSnapshot: GlobalSnapshot
    ): Snapshot {
        // TODO(dj): This is where serious refactoring needs to be done
        val egressDomainRouteSpecifications = getDomainRouteSpecifications(group)
        val egressServiceRouteSpecification = getServiceRouteSpecifications(group, globalSnapshot)
        val egressRouteSpecification = egressServiceRouteSpecification +
            egressDomainRouteSpecifications.values.flatten().toSet() +
            getDomainPatternRouteSpecifications(group)

        val clusters: List<Cluster> =
            clustersFactory.getClustersForGroup(group, globalSnapshot)

        val routes = mutableListOf(
            ingressRoutesFactory.createSecuredIngressRouteConfig(group.serviceName, group.proxySettings, group)
        )

        if (group.listenersConfig?.useTransparentProxy == true) {
            createRoutesWhenUsingTransparentProxy(
                routes,
                group,
                egressServiceRouteSpecification,
                egressDomainRouteSpecifications
            )
        } else {
            routes.add(
                egressRoutesFactory.createEgressRouteConfig(
                    serviceName = group.serviceName,
                    routes = egressRouteSpecification,
                    addUpstreamAddressHeader = group.listenersConfig.orDefault().addUpstreamExternalAddressHeader,
                    addUpstreamServiceTagsHeader = group.listenersConfig.orDefault().addUpstreamServiceTags
                )
            )
        }
        val listeners = if (properties.dynamicListeners.enabled) {
            listenersFactory.createListeners(group, globalSnapshot)
        } else {
            emptyList()
        }

        // TODO(dj): endpoints depends on prerequisite of routes -> but only to extract clusterName,
        // which is present only in services (not domains) so it could be implemented differently.
        val endpoints = getServicesEndpointsForGroup(
            group.proxySettings.incoming.rateLimitEndpoints, globalSnapshot,
            egressRouteSpecification
        )

        val version = snapshotsVersions.version(group, clusters, endpoints, listeners, routes)
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

    private fun createRoutesWhenUsingTransparentProxy(
        routes: MutableList<RouteConfiguration>,
        group: Group,
        egressRouteSpecification: Collection<RouteSpecification>,
        egressDomainRouteSpecifications: Map<DomainRoutesGrouper, Collection<RouteSpecification>>
    ) {
        // routes for listener binded to port
        routes.add(
            egressRoutesFactory.createEgressRouteConfig(
                group.serviceName, emptyList(),
                group.listenersConfig.orDefault().addUpstreamExternalAddressHeader
            )
        )
        // routes for listener on port http = 80
        routes.add(
            egressRoutesFactory.createEgressRouteConfig(
                serviceName = group.serviceName,
                routes = egressRouteSpecification +
                    egressDomainRouteSpecifications.getOrDefault(
                        DomainRoutesGrouper(DEFAULT_HTTP_PORT, false), emptyList()
                    ),
                addUpstreamAddressHeader = group.listenersConfig.orDefault().addUpstreamExternalAddressHeader,
                routeName = DEFAULT_HTTP_PORT.toString()
            )
        )

        // routes for listeners different than port 80 and not ssl, because ssl is handled by tcp proxy
        egressDomainRouteSpecifications
            .filter { it.key.port != DEFAULT_HTTP_PORT && !it.key.useSsl }
            .forEach {
                routes.add(
                    egressRoutesFactory.createEgressDomainRoutes(
                        it.value,
                        it.key.port.toString().toLowerCase()
                    )
                )
            }
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

data class DomainRoutesGrouper(
    val port: Int,
    val useSsl: Boolean
)

data class ClusterConfiguration(
    val serviceName: String,
    val http2Enabled: Boolean
)

sealed class RouteSpecification {
    abstract val clusterName: String
    abstract val routeDomains: List<String>
    abstract val settings: DependencySettings
}

data class StandardRouteSpecification(
    override val clusterName: String,
    override val routeDomains: List<String>,
    override val settings: DependencySettings,
) : RouteSpecification()

data class WeightRouteSpecification(
    override val clusterName: String,
    override val routeDomains: List<String>,
    override val settings: DependencySettings,
    val clusterWeights: ZoneWeights,
) : RouteSpecification()
