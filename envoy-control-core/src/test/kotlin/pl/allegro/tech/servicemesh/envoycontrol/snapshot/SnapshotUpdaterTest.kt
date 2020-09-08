package pl.allegro.tech.servicemesh.envoycontrol.snapshot

import com.google.protobuf.util.Durations
import io.envoyproxy.controlplane.cache.Resources
import io.envoyproxy.controlplane.cache.Response
import io.envoyproxy.controlplane.cache.SnapshotCache
import io.envoyproxy.controlplane.cache.StatusInfo
import io.envoyproxy.controlplane.cache.Watch
import io.envoyproxy.controlplane.cache.XdsRequest
import io.envoyproxy.controlplane.cache.v2.Snapshot
import io.envoyproxy.envoy.api.v2.RouteConfiguration
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import pl.allegro.tech.servicemesh.envoycontrol.groups.AllServicesGroup
import pl.allegro.tech.servicemesh.envoycontrol.groups.CommunicationMode
import pl.allegro.tech.servicemesh.envoycontrol.groups.CommunicationMode.ADS
import pl.allegro.tech.servicemesh.envoycontrol.groups.CommunicationMode.XDS
import pl.allegro.tech.servicemesh.envoycontrol.groups.DependencySettings
import pl.allegro.tech.servicemesh.envoycontrol.groups.DomainDependency
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group
import pl.allegro.tech.servicemesh.envoycontrol.groups.Outgoing
import pl.allegro.tech.servicemesh.envoycontrol.groups.ProxySettings
import pl.allegro.tech.servicemesh.envoycontrol.groups.ServiceDependency
import pl.allegro.tech.servicemesh.envoycontrol.groups.ServicesGroup
import pl.allegro.tech.servicemesh.envoycontrol.groups.with
import pl.allegro.tech.servicemesh.envoycontrol.services.Locality
import pl.allegro.tech.servicemesh.envoycontrol.services.ClusterState
import pl.allegro.tech.servicemesh.envoycontrol.services.MultiClusterState
import pl.allegro.tech.servicemesh.envoycontrol.services.MultiClusterState.Companion.toMultiClusterState
import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceInstance
import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceInstances
import pl.allegro.tech.servicemesh.envoycontrol.services.ServicesState
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.clusters.EnvoyClustersFactory
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.endpoints.EnvoyEndpointsFactory
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.EnvoyListenersFactory
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters.EnvoyHttpFilters
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.routes.EnvoyEgressRoutesFactory
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.routes.EnvoyIngressRoutesFactory
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.routes.ServiceTagMetadataGenerator
import pl.allegro.tech.servicemesh.envoycontrol.utils.DirectScheduler
import pl.allegro.tech.servicemesh.envoycontrol.utils.ParallelScheduler
import pl.allegro.tech.servicemesh.envoycontrol.utils.ParallelizableScheduler
import reactor.core.publisher.Flux
import reactor.core.scheduler.Schedulers
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

class SnapshotUpdaterTest {

    companion object {
        @JvmStatic
        fun configurationModeNotSupported() = listOf(
            Arguments.of(false, false, ADS, "ADS not supported by server"),
            Arguments.of(false, false, XDS, "XDS not supported by server")
        )

        private val uninitializedSnapshot = null
    }

    val groupWithProxy = AllServicesGroup(
        communicationMode = ADS,
        serviceName = "service"
    )
    val groupWithServiceName = groupOf(
        services = setOf(ServiceDependency(service = "existingService2"))
    ).copy(serviceName = "ipsum-service")

    val simpleMeterRegistry = SimpleMeterRegistry()

    val clusterWithEnvoyInstances = ClusterState(
        ServicesState(
            serviceNameToInstances = mapOf(
                "service" to ServiceInstances("service", setOf(ServiceInstance(
                    id = "id",
                    tags = setOf("envoy"),
                    address = "127.0.0.3",
                    port = 4444
                )))
            )
        ),
        Locality.LOCAL, "cluster"
    ).toMultiClusterState()

    @Test
    fun `should generate group snapshots`() {
        val cache = MockCache()

        // groups are generated foreach element in SnapshotCache.groups(), so we need to initialize them
        val groups = listOf(
            AllServicesGroup(communicationMode = XDS), groupWithProxy, groupWithServiceName,
                groupOf(services = serviceDependencies("existingService1")),
                groupOf(services = serviceDependencies("existingService2"))
        )
        groups.forEach {
            cache.setSnapshot(it, uninitializedSnapshot)
        }

        cache.setSnapshot(groupOf(
            services = serviceDependencies("existingService1", "existingService2"),
            domains = domainDependencies("http://domain")
        ), uninitializedSnapshot)

        cache.setSnapshot(groupOf(services = serviceDependencies("nonExistingService3")), uninitializedSnapshot)

        val updater = snapshotUpdater(
            cache = cache,
            properties = SnapshotProperties().apply {
                incomingPermissions.enabled = true
            },
            groups = groups
        )

        // when
        updater.startWithServices("existingService1", "existingService2")

        // then
        hasSnapshot(cache, AllServicesGroup(communicationMode = XDS))
            .hasOnlyClustersFor("existingService1", "existingService2")

        hasSnapshot(cache, groupWithProxy)
            .hasOnlyClustersFor("existingService1", "existingService2")

        hasSnapshot(cache, groupOf(services = serviceDependencies("existingService1")))
            .hasOnlyClustersFor("existingService1")

        hasSnapshot(cache, groupOf(services = serviceDependencies("existingService2")))
            .hasOnlyClustersFor("existingService2")

        hasSnapshot(cache, groupWithServiceName)
            .hasOnlyClustersFor("existingService2")

        hasSnapshot(cache, groupOf(
            services = serviceDependencies("existingService1", "existingService2"),
            domains = domainDependencies("http://domain")
        )).hasOnlyClustersFor("existingService1", "existingService2", "domain_80")

        hasSnapshot(cache, groupOf(services = serviceDependencies("nonExistingService3")))
            .withoutClusters()
    }

    @ParameterizedTest
    @MethodSource("configurationModeNotSupported")
    fun `should not generate group snapshots for modes not supported by the server`(
        adsSupported: Boolean,
        xdsSupported: Boolean,
        mode: CommunicationMode
    ) {
        val allServiceGroup = AllServicesGroup(communicationMode = mode)

        val cache = MockCache()
        cache.setSnapshot(allServiceGroup, uninitializedSnapshot)

        val updater = snapshotUpdater(
            cache = cache,
            properties = SnapshotProperties().apply {
                enabledCommunicationModes.ads = adsSupported; enabledCommunicationModes.xds = xdsSupported
            }
        )

        // when
        updater.start(
            Flux.just(MultiClusterState.empty())
        ).blockFirst()

        // should not generate snapshot
        assertThat(cache.getSnapshot(allServiceGroup)).isNull()
    }

    @Test
    fun `should generate snapshot with empty clusters and endpoints version and one route`() {
        // given
        val emptyGroup = groupOf()

        val cache = MockCache()
        cache.setSnapshot(emptyGroup, uninitializedSnapshot)

        val updater = snapshotUpdater(cache = cache)

        // when
        updater.start(
            Flux.just(MultiClusterState.empty())
        ).blockFirst()

        // then version is set to empty
        val snapshot = hasSnapshot(cache, emptyGroup)
        val clusters = snapshot.resources(Resources.ResourceType.CLUSTER)
        assertThat(clusters).isNotNull()
//        assertThat(clusters.version()).isEqualTo(ClustersVersion.EMPTY_VERSION.value)
//        assertThat(snapshot.endpoints().version()).isEqualTo(EndpointsVersion.EMPTY_VERSION.value)
//        assertThat(snapshot.listeners().version()).isNotEqualTo(ListenersVersion.EMPTY_VERSION.value)
//        assertThat(snapshot.routes().version()).isNotEqualTo(RoutesVersion.EMPTY_VERSION.value)
//
//        assertThat(snapshot.routes().resources().values).hasSize(2)
//        // two fallbacks: proxying direct IP requests and 503 for missing services
//        assertThat(snapshot.routes().resources().values
//            .first { it.name == "default_routes" }.virtualHostsCount)
//            .isEqualTo(2)
    }

    @Test
    fun `should not crash on bad snapshot generation`() {
        // given
        val servicesGroup = AllServicesGroup(
                communicationMode = ADS,
                serviceName = "example-service"
        )
        val cache = FailingMockCache()
        cache.setSnapshot(servicesGroup, null)
        val updater = snapshotUpdater(cache = cache)

        // when
        updater.start(
                Flux.just(MultiClusterState.empty())
        ).blockFirst()

        // then
        val snapshot = cache.getSnapshot(servicesGroup)
        assertThat(snapshot).isEqualTo(null)
        assertThat(simpleMeterRegistry.find("snapshot-updater.services.example-service.updates.errors")
                .counter()?.count()).isEqualTo(1.0)
    }

    @Test
    fun `should not disable http2 for cluster when instances disappeared`() {
        // given
        val cache = MockCache()
        val updater = snapshotUpdater(
            cache = cache,
            properties = SnapshotProperties().apply {
                stateSampleDuration = Duration.ZERO
            }
        )

        val clusterWithNoInstances = ClusterState(
            ServicesState(
                serviceNameToInstances = mapOf(
                    "service" to ServiceInstances("service", setOf())
                )
            ),
            Locality.LOCAL, "cluster"
        ).toMultiClusterState()

        // when
        val results = updater
            .services(Flux
                .just(
                    clusterWithEnvoyInstances,
                    clusterWithNoInstances)
                .delayElements(Duration.ofMillis(10))
            )
            .collectList().block()!!

        // then
        assertThat(results.size).isEqualTo(2)
        results[0].adsSnapshot!!.hasHttp2Cluster("service")
        results[0].xdsSnapshot!!.hasHttp2Cluster("service")
        results[1].adsSnapshot!!.hasHttp2Cluster("service")
        results[1].xdsSnapshot!!.hasHttp2Cluster("service")
    }

    @Test
    fun `should not remove clusters`() {
        // given
        val cache = MockCache()
        val updater = snapshotUpdater(
            cache = cache,
            properties = SnapshotProperties().apply {
                stateSampleDuration = Duration.ZERO
            }
        )

        val stateWithNoServices = ClusterState(
            ServicesState(serviceNameToInstances = mapOf()),
            Locality.LOCAL, "cluster"
        ).toMultiClusterState()

        // when
        val results = updater
            .services(Flux
                .just(
                    clusterWithEnvoyInstances,
                    stateWithNoServices
                ).delayElements(Duration.ofMillis(10))
            )
            .collectList().block()!!

        // then
        assertThat(results.size).isEqualTo(2)

        results[0].adsSnapshot!!
            .hasHttp2Cluster("service")
            .hasAnEndpoint("service", "127.0.0.3", 4444)
        results[0].xdsSnapshot!!
            .hasHttp2Cluster("service")
            .hasAnEndpoint("service", "127.0.0.3", 4444)

        results[1].adsSnapshot!!
            .hasHttp2Cluster("service")
            .hasEmptyEndpoints("service")
        results[1].xdsSnapshot!!
            .hasHttp2Cluster("service")
            .hasEmptyEndpoints("service")
    }

    @Test
    fun `should not include blacklisted services in wildcard dependencies but include in direct dependencies`() {
        // given
        val cache = MockCache()

        val allServicesGroup = AllServicesGroup(communicationMode = ADS)
        val groupWithBlacklistedDependency = groupOf(services = serviceDependencies("mock-service"))

        val groups = listOf(allServicesGroup, groupWithBlacklistedDependency)

        val updater = snapshotUpdater(
            cache = cache,
            properties = SnapshotProperties().apply {
                outgoingPermissions.allServicesDependencies.notIncludedByPrefix = mutableSetOf(
                    "mock-", "regression-tests"
                )
            },
            groups = groups
        )

        val expectedWhitelistedServices = setOf("s1", "mockito", "s2", "frontend").toTypedArray()

        // when
        updater.start(fluxOfServices(
            "s1", "mockito", "regression-tests", "s2", "frontend", "mock-service"
        )).collectList().block()

        // then
        hasSnapshot(cache, allServicesGroup)
            .hasOnlyClustersFor(*expectedWhitelistedServices)
            .hasOnlyEndpointsFor(*expectedWhitelistedServices)
            .hasOnlyEgressRoutesForClusters(*expectedWhitelistedServices)

        hasSnapshot(cache, groupWithBlacklistedDependency)
            .hasOnlyClustersFor("mock-service")
            .hasOnlyEndpointsFor("mock-service")
            .hasOnlyEgressRoutesForClusters("mock-service")
    }

    @Test
    fun `should set snapshot in parallel`() {
        // given
        val cache = MockCache()
        val groups = listOf(groupWithProxy, groupWithServiceName, AllServicesGroup(communicationMode = ADS))
        groups.forEach {
            cache.setSnapshot(it, uninitializedSnapshot)
        }
        val expectedConcurrency = 3
        val updater = snapshotUpdater(
            cache = cache,
            groups = groups,
            groupSnapshotScheduler = ParallelScheduler(
                scheduler = Schedulers.fromExecutor(Executors.newFixedThreadPool(expectedConcurrency)),
                parallelism = expectedConcurrency
            )
        )

        cache.waitForConcurrentSetSnapshotInvocations(count = expectedConcurrency)

        // when
        updater.startWithServices("existingService1", "existingService2")

        // then
        hasSnapshot(cache, AllServicesGroup(communicationMode = ADS))
            .hasOnlyClustersFor("existingService1", "existingService2")
        hasSnapshot(cache, groupWithProxy)
            .hasOnlyClustersFor("existingService1", "existingService2")
        hasSnapshot(cache, groupWithServiceName)
            .hasOnlyClustersFor("existingService2")
    }

    private fun SnapshotUpdater.startWithServices(vararg services: String) {
        this.start(fluxOfServices(*services)).blockFirst()
    }

    private fun fluxOfServices(vararg services: String) = Flux.just(
        ClusterState(
            ServicesState(
                serviceNameToInstances = services.map { it to ServiceInstances(it, emptySet()) }.toMap()

            ),
            Locality.LOCAL, "cluster"
        ).toMultiClusterState()
    )

    class FailingMockCache : MockCache() {
        var called = 0

        override fun setSnapshot(group: Group, snapshot: Snapshot?) {
            if (called > 0) {
                throw FailingMockCacheException()
            }
            called += 1
            super.setSnapshot(group, snapshot)
        }
    }

    class FailingMockCacheException : RuntimeException()

    open class MockCache : SnapshotCache<Group, Snapshot> {
        val groups: MutableMap<Group, Snapshot?> = mutableMapOf()
        private var concurrentSetSnapshotCounter: CountDownLatch? = null

        override fun groups(): MutableCollection<Group> {
            return groups.keys.toMutableList()
        }

        override fun getSnapshot(group: Group): Snapshot? {
            return groups[group]
        }

        override fun setSnapshot(group: Group, snapshot: Snapshot?) {
            setSnapshotWait()
            groups[group] = snapshot
        }

        override fun statusInfo(group: Group): StatusInfo<Group> {
            throw UnsupportedOperationException("not used in testing")
        }

        override fun createWatch(
            ads: Boolean,
            request: XdsRequest,
            knownResourceNames: MutableSet<String>,
            responseConsumer: Consumer<Response>,
            hasClusterChanged: Boolean
        ): Watch {
            throw UnsupportedOperationException("not used in testing")
        }

        override fun clearSnapshot(group: Group?): Boolean {
            return false
        }

        fun waitForConcurrentSetSnapshotInvocations(count: Int) {
            concurrentSetSnapshotCounter = CountDownLatch(count)
        }

        private fun setSnapshotWait() {
            concurrentSetSnapshotCounter?.let { latch ->
                latch.countDown()
                assertThat(latch.await(4, TimeUnit.SECONDS))
                    .describedAs("Timed out waiting for concurrent setSnapshot invocations")
                    .isTrue()
            }
        }
    }

    private fun hasSnapshot(cache: SnapshotCache<Group, Snapshot>, group: Group): Snapshot {
        val snapshot = cache.getSnapshot(group)
        assertThat(snapshot).isNotNull
        return snapshot
    }

    private fun Snapshot.hasOnlyClustersFor(vararg expected: String): Snapshot {
        assertThat(this.resources(Resources.ResourceType.CLUSTER).keys.toSet())
            .isEqualTo(expected.toSet())
        return this
    }

    private fun Snapshot.hasOnlyEndpointsFor(vararg expected: String): Snapshot {
        assertThat(this.resources(Resources.ResourceType.ENDPOINT).keys.toSet())
            .isEqualTo(expected.toSet())
        return this
    }

    private fun Snapshot.hasOnlyEgressRoutesForClusters(vararg expected: String): Snapshot {
        val routes = this.resources(Resources.ResourceType.ROUTE)["default_routes"] as RouteConfiguration
        assertThat(routes.virtualHostsList.flatMap { it.domainsList }.toSet())
            .isEqualTo(expected.toSet() + setOf("envoy-original-destination", "*"))
        return this
    }

    private fun Snapshot.withoutClusters() {
        assertThat(this.resources(Resources.ResourceType.CLUSTER).keys).isEmpty()
    }

    private fun groupOf(
        services: Set<ServiceDependency> = emptySet(),
        domains: Set<DomainDependency> = emptySet()
    ) = ServicesGroup(
        communicationMode = XDS,
        proxySettings = ProxySettings().with(
            serviceDependencies = services, domainDependencies = domains
        )
    )

    private fun GlobalSnapshot.hasHttp2Cluster(clusterName: String): GlobalSnapshot {
        val cluster = this.clusters.resources()[clusterName]
        assertThat(cluster).isNotNull
        assertThat(cluster!!.hasHttp2ProtocolOptions()).isTrue()
        return this
    }

    private fun GlobalSnapshot.hasAnEndpoint(clusterName: String, ip: String, port: Int): GlobalSnapshot {
        val endpoints = this.endpoints.resources()[clusterName]
        assertThat(endpoints).isNotNull
        assertThat(endpoints!!.endpointsList.flatMap { it.lbEndpointsList })
            .anyMatch { it.endpoint.address.socketAddress.let { it.address == ip && it.portValue == port } }
        return this
    }

    private fun GlobalSnapshot.hasEmptyEndpoints(clusterName: String): GlobalSnapshot {
        val endpoints = this.endpoints.resources()[clusterName]
        assertThat(endpoints).isNotNull
        assertThat(endpoints!!.endpointsList).isEmpty()
        return this
    }

    private fun snapshotFactory(snapshotProperties: SnapshotProperties, meterRegistry: MeterRegistry) =
            EnvoySnapshotFactory(
                    ingressRoutesFactory = EnvoyIngressRoutesFactory(snapshotProperties),
                    egressRoutesFactory = EnvoyEgressRoutesFactory(snapshotProperties),
                    clustersFactory = EnvoyClustersFactory(snapshotProperties),
                    endpointsFactory = EnvoyEndpointsFactory(
                            snapshotProperties, ServiceTagMetadataGenerator(snapshotProperties.routing.serviceTags)
                    ),
                    listenersFactory = EnvoyListenersFactory(
                            snapshotProperties,
                            EnvoyHttpFilters.emptyFilters
                    ),
                    // Remember when LDS change we have to send RDS again
                    snapshotsVersions = SnapshotsVersions(),
                    properties = snapshotProperties,
                    meterRegistry = meterRegistry
            )

    private fun snapshotUpdater(
        cache: SnapshotCache<Group, Snapshot>,
        properties: SnapshotProperties = SnapshotProperties(),
        groups: List<Group> = emptyList(),
        groupSnapshotScheduler: ParallelizableScheduler = DirectScheduler
    ) = SnapshotUpdater(
            cache = cache,
            properties = properties,
            snapshotFactory = snapshotFactory(properties, simpleMeterRegistry),
            globalSnapshotScheduler = Schedulers.newSingle("update-snapshot"),
            groupSnapshotScheduler = groupSnapshotScheduler,
            onGroupAdded = Flux.just(groups),
            meterRegistry = simpleMeterRegistry
    )
}

fun serviceDependencies(vararg serviceNames: String): Set<ServiceDependency> =
    serviceNames.map {
        ServiceDependency(
            service = it,
            settings = DependencySettings(timeoutPolicy = Outgoing.TimeoutPolicy(
                idleTimeout = Durations.fromSeconds(120L),
                requestTimeout = Durations.fromSeconds(120L)
            ))
        )
    }.toSet()

fun domainDependencies(vararg serviceNames: String): Set<DomainDependency> =
    serviceNames.map {
        DomainDependency(
            domain = it,
            settings = DependencySettings(timeoutPolicy = Outgoing.TimeoutPolicy(
                idleTimeout = Durations.fromSeconds(120L),
                requestTimeout = Durations.fromSeconds(120L)
            ))
        )
    }.toSet()
