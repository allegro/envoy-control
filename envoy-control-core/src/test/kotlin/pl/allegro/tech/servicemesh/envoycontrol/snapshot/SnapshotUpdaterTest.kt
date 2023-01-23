package pl.allegro.tech.servicemesh.envoycontrol.snapshot

import com.google.protobuf.util.Durations
import io.envoyproxy.controlplane.cache.DeltaResponse
import io.envoyproxy.controlplane.cache.DeltaWatch
import io.envoyproxy.controlplane.cache.DeltaXdsRequest
import io.envoyproxy.controlplane.cache.Resources
import io.envoyproxy.controlplane.cache.Response
import io.envoyproxy.controlplane.cache.SnapshotCache
import io.envoyproxy.controlplane.cache.StatusInfo
import io.envoyproxy.controlplane.cache.Watch
import io.envoyproxy.controlplane.cache.XdsRequest
import io.envoyproxy.controlplane.cache.v3.Snapshot
import io.envoyproxy.envoy.config.cluster.v3.Cluster
import io.envoyproxy.envoy.config.listener.v3.Listener
import io.envoyproxy.envoy.config.route.v3.RetryPolicy
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.mockito.Mockito
import pl.allegro.tech.servicemesh.envoycontrol.groups.AccessLogFilterSettings
import pl.allegro.tech.servicemesh.envoycontrol.groups.AllServicesGroup
import pl.allegro.tech.servicemesh.envoycontrol.groups.CommunicationMode
import pl.allegro.tech.servicemesh.envoycontrol.groups.CommunicationMode.ADS
import pl.allegro.tech.servicemesh.envoycontrol.groups.CommunicationMode.XDS
import pl.allegro.tech.servicemesh.envoycontrol.groups.DependencySettings
import pl.allegro.tech.servicemesh.envoycontrol.groups.DomainDependency
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group
import pl.allegro.tech.servicemesh.envoycontrol.groups.ListenersConfig
import pl.allegro.tech.servicemesh.envoycontrol.groups.Outgoing
import pl.allegro.tech.servicemesh.envoycontrol.groups.ProxySettings
import pl.allegro.tech.servicemesh.envoycontrol.groups.RetryBackOff
import pl.allegro.tech.servicemesh.envoycontrol.groups.RetryHostPredicate
import pl.allegro.tech.servicemesh.envoycontrol.groups.ServiceDependency
import pl.allegro.tech.servicemesh.envoycontrol.groups.ServicesGroup
import pl.allegro.tech.servicemesh.envoycontrol.groups.with
import pl.allegro.tech.servicemesh.envoycontrol.services.ClusterState
import pl.allegro.tech.servicemesh.envoycontrol.services.Locality
import pl.allegro.tech.servicemesh.envoycontrol.services.MultiClusterState
import pl.allegro.tech.servicemesh.envoycontrol.services.MultiClusterState.Companion.toMultiClusterState
import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceInstance
import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceInstances
import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceName
import pl.allegro.tech.servicemesh.envoycontrol.services.ServicesState
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.clusters.EnvoyClustersFactory
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.endpoints.EnvoyEndpointsFactory
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.EnvoyListenersFactory
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters.EnvoyHttpFilters
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.routes.EnvoyEgressRoutesFactory
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.routes.EnvoyIngressRoutesFactory
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.routes.RequestPolicyMapper
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.routes.ServiceTagMetadataGenerator
import pl.allegro.tech.servicemesh.envoycontrol.utils.DirectScheduler
import pl.allegro.tech.servicemesh.envoycontrol.utils.ParallelScheduler
import pl.allegro.tech.servicemesh.envoycontrol.utils.ParallelizableScheduler
import pl.allegro.tech.servicemesh.envoycontrol.utils.any
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import pl.allegro.tech.servicemesh.envoycontrol.groups.RateLimitedRetryBackOff as EnvoyControlRateLimitedRetryBackOff
import pl.allegro.tech.servicemesh.envoycontrol.groups.ResetHeader as EnvoyControlResetHeader
import pl.allegro.tech.servicemesh.envoycontrol.groups.RetryPolicy as EnvoyControlRetryPolicy

@Suppress("LargeClass")
class SnapshotUpdaterTest {

    companion object {
        @JvmStatic
        fun configurationModeNotSupported() = listOf(
            Arguments.of(false, false, ADS, "ADS not supported by server"),
            Arguments.of(false, false, XDS, "XDS not supported by server")
        )

        @JvmStatic
        fun tapConfiguration() = listOf(
            Arguments.of(false, Snapshot::allClustersHasNotConfiguredTap),
            Arguments.of(true, Snapshot::allClustersHasConfiguredTap)
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
            serviceNameToInstances = concurrentMapOf(
                "service" to ServiceInstances(
                    "service", setOf(
                        ServiceInstance(
                            id = "id",
                            tags = setOf("envoy"),
                            address = "127.0.0.3",
                            port = 4444
                        )
                    )
                )
            )
        ),
        Locality.LOCAL, "cluster"
    ).toMultiClusterState()

    @Test
    fun `should generate allServicesGroup snapshots with timeouts from proxySettings`() {
        val cache = MockCache()

        val allServicesGroup = AllServicesGroup(
            communicationMode = XDS, proxySettings = ProxySettings(
                outgoing = Outgoing(
                    serviceDependencies = listOf(
                        ServiceDependency(
                            service = "existingService1",
                            settings = DependencySettings(
                                timeoutPolicy = Outgoing.TimeoutPolicy(
                                    idleTimeout = Durations.parse("10s"),
                                    requestTimeout = Durations.parse("9s")
                                )
                            )
                        )
                    ),
                    defaultServiceSettings = DependencySettings(
                        timeoutPolicy = Outgoing.TimeoutPolicy(
                            idleTimeout = Durations.parse("8s"),
                            requestTimeout = Durations.parse("7s")
                        )
                    ),
                    allServicesDependencies = true
                )
            )
        )

        cache.setSnapshot(allServicesGroup, uninitializedSnapshot)

        val updater = snapshotUpdater(
            cache = cache,
            properties = SnapshotProperties().apply {
                incomingPermissions.enabled = true
            },
            groups = listOf(allServicesGroup)
        )

        updater.startWithServices("existingService1", "existingService2")

        hasSnapshot(cache, allServicesGroup)
            .hasOnlyClustersFor("existingService1", "existingService2")
            .hasVirtualHostConfig(name = "existingService1", idleTimeout = "10s", requestTimeout = "9s")
            .hasVirtualHostConfig(name = "existingService2", idleTimeout = "8s", requestTimeout = "7s")
    }

    @Test
    fun `should audit snapshot changes`() {
        val cache = MockCache()

        val snapshotAuditor = Mockito.mock(SnapshotChangeAuditor::class.java)
        Mockito.`when`(snapshotAuditor.audit(any(UpdateResult::class.java), any(UpdateResult::class.java)))
            .thenReturn(Mono.empty())
        val groups = listOf(groupWithProxy, groupWithServiceName, AllServicesGroup(communicationMode = ADS))
        groups.forEach {
            cache.setSnapshot(it, uninitializedSnapshot)
        }

        val updater = snapshotUpdater(
            cache = cache,
            snapshotChangeAuditor = snapshotAuditor,
            properties = SnapshotProperties().apply {
                stateSampleDuration = Duration.ZERO
            },
            groups = groups
        )

        // when
        updater.startWithServices(
            arrayOf("existingService1"),
            arrayOf("existingService1", "existingService2"),
            arrayOf("existingService3")
        )

        // then
        Mockito.verify(snapshotAuditor, Mockito.times(3))
            .audit(any(UpdateResult::class.java), any(UpdateResult::class.java))
    }

    @Test
    fun `should generate allServicesGroup snapshots with timeouts from proxySettings and retry policy`() {
        val cache = MockCache()
        val givenRetryPolicy = EnvoyControlRetryPolicy(
            retryOn = listOf("givenRetryOn"),
            hostSelectionRetryMaxAttempts = 1,
            numberRetries = 2,
            perTryTimeoutMs = 3,
            retryableHeaders = listOf("givenTestHeader"),
            retryableStatusCodes = listOf(504),
            retryBackOff = RetryBackOff(
                baseInterval = Durations.fromMillis(123),
                maxInterval = Durations.fromMillis(234)
            ),
            retryHostPredicate = listOf(RetryHostPredicate.PREVIOUS_HOSTS),
            methods = setOf("POST")
        )
        val allServicesGroup = AllServicesGroup(
            communicationMode = XDS, proxySettings = ProxySettings(
                outgoing = Outgoing(
                    serviceDependencies = listOf(
                        ServiceDependency(
                            service = "retryPolicyService1",
                            settings = DependencySettings(
                                timeoutPolicy = Outgoing.TimeoutPolicy(
                                    idleTimeout = Durations.parse("10s"),
                                    requestTimeout = Durations.parse("9s")
                                ),
                                retryPolicy = givenRetryPolicy
                            )
                        )
                    ),
                    defaultServiceSettings = DependencySettings(
                        timeoutPolicy = Outgoing.TimeoutPolicy(
                            idleTimeout = Durations.parse("8s"),
                            requestTimeout = Durations.parse("7s")
                        )
                    ),
                    allServicesDependencies = true
                )
            )
        )

        cache.setSnapshot(allServicesGroup, uninitializedSnapshot)

        val updater = snapshotUpdater(
            cache = cache,
            properties = SnapshotProperties().apply {
                incomingPermissions.enabled = true
            },
            groups = listOf(allServicesGroup)
        )

        updater.startWithServices("retryPolicyService1", "retryPolicyService2")

        hasSnapshot(cache, allServicesGroup)
            .hasOnlyClustersFor("retryPolicyService1", "retryPolicyService2")
            .hasVirtualHostConfig(name = "retryPolicyService1", idleTimeout = "10s", requestTimeout = "9s")
            .hasVirtualHostConfig(name = "retryPolicyService2", idleTimeout = "8s", requestTimeout = "7s")
            .hasRetryPolicySpecified(
                name = "retryPolicyService1",
                retryPolicy = RequestPolicyMapper.mapToEnvoyRetryPolicyBuilder(givenRetryPolicy)
            )
            .hasRetryPolicySpecified(name = "retryPolicyService2", retryPolicy = null)
    }

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

        cache.setSnapshot(
            groupOf(
                services = serviceDependencies("existingService1", "existingService2"),
                domains = domainDependencies("http://domain")
            ), uninitializedSnapshot
        )

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

        hasSnapshot(
            cache, groupOf(
                services = serviceDependencies("existingService1", "existingService2"),
                domains = domainDependencies("http://domain")
            )
        ).hasOnlyClustersFor("existingService1", "existingService2", "domain_80")

        hasSnapshot(cache, groupOf(services = serviceDependencies("nonExistingService3")))
            .withoutClusters()
    }

    @ParameterizedTest
    @MethodSource("tapConfiguration")
    fun `should properly generate tap configuration for all clusters`(
        createTapConfiguration: Boolean,
        tapConfigurationVerifier: Snapshot.() -> Unit
    ) {
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

        cache.setSnapshot(
            groupOf(
                services = serviceDependencies("existingService1", "existingService2"),
                domains = domainDependencies("http://domain")
            ), uninitializedSnapshot
        )

        cache.setSnapshot(groupOf(services = serviceDependencies("nonExistingService3")), uninitializedSnapshot)

        val updater = snapshotUpdater(
            cache = cache,
            properties = SnapshotProperties().apply {
                tcpDumpsEnabled = createTapConfiguration
            },
            groups = groups
        )

        // when
        updater.startWithServices("existingService1", "existingService2")

        // then
        hasSnapshot(cache, AllServicesGroup(communicationMode = XDS))
            .tapConfigurationVerifier()

        hasSnapshot(cache, groupWithProxy)
            .tapConfigurationVerifier()

        hasSnapshot(cache, groupOf(services = serviceDependencies("existingService1")))
            .tapConfigurationVerifier()

        hasSnapshot(cache, groupOf(services = serviceDependencies("existingService2")))
            .tapConfigurationVerifier()

        hasSnapshot(cache, groupWithServiceName)
            .tapConfigurationVerifier()

        hasSnapshot(
            cache, groupOf(
                services = serviceDependencies("existingService1", "existingService2"),
                domains = domainDependencies("http://domain")
            )
        ).tapConfigurationVerifier()
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
        assertThat(snapshot.clusters().version()).isEqualTo(ClustersVersion.EMPTY_VERSION.value)
        assertThat(snapshot.endpoints().version()).isEqualTo(EndpointsVersion.EMPTY_VERSION.value)
        assertThat(snapshot.listeners().version()).isNotEqualTo(ListenersVersion.EMPTY_VERSION.value)
        assertThat(snapshot.routes().version()).isNotEqualTo(RoutesVersion.EMPTY_VERSION.value)

        assertThat(snapshot.routes().resources().values).hasSize(2)
        // two fallbacks: proxying direct IP requests and 503 for missing services
        assertThat(
            snapshot.routes().resources().values
                .first { it.name == "default_routes" }.virtualHostsCount
        )
            .isEqualTo(2)
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
        assertThat(
            simpleMeterRegistry.find("snapshot-updater.services.example-service.updates.errors")
                .counter()?.count()
        ).isEqualTo(1.0)
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
                serviceNameToInstances = concurrentMapOf(
                    "service" to ServiceInstances("service", setOf())
                )
            ),
            Locality.LOCAL, "cluster"
        ).toMultiClusterState()

        // when
        val results = updater
            .services(
                Flux
                    .just(
                        clusterWithEnvoyInstances,
                        clusterWithNoInstances
                    )
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
    fun `should not change CDS version when service order in remote cluster is different`() {
        // given
        val cache = MockCache()
        val allServicesGroup = AllServicesGroup(communicationMode = ADS)
        val groups = listOf(allServicesGroup)
        val updater = snapshotUpdater(
            cache = cache,
            properties = SnapshotProperties().apply {
                stateSampleDuration = Duration.ZERO
            },
            groups = groups
        )

        val clusterWithOrder = ClusterState(
            ServicesState(
                serviceNameToInstances = concurrentMapOf(
                    "service" to ServiceInstances("service", setOf()),
                    "service2" to ServiceInstances("service2", setOf())
                )
            ),
            Locality.LOCAL, "cluster"
        ).toMultiClusterState()
        val clusterWithoutOrder = ClusterState(
            ServicesState(
                serviceNameToInstances = concurrentMapOf(
                    "service2" to ServiceInstances("service2", setOf()),
                    "service" to ServiceInstances("service", setOf())
                )
            ),
            Locality.REMOTE, "cluster2"
        ).toMultiClusterState()

        // when
        updater.start(
            Flux.just(clusterWithoutOrder, clusterWithOrder)
        ).collectList().block()
        val previousClusters = cache.getSnapshot(allServicesGroup)!!.clusters()

        // when
        updater.start(
            Flux.just(clusterWithOrder, clusterWithoutOrder)
        ).collectList().block()
        val currentClusters = cache.getSnapshot(allServicesGroup)!!.clusters()

        // then
        assertThat(previousClusters.version()).isEqualTo(currentClusters.version())
        assertThat(previousClusters.resources()).isEqualTo(currentClusters.resources())
    }

    @Test
    fun `should not change EDS when remote doesn't have state of service`() {
        // given
        val cache = MockCache()
        val allServicesGroup = AllServicesGroup(communicationMode = ADS)
        val groups = listOf(allServicesGroup)
        val updater = snapshotUpdater(
            cache = cache,
            properties = SnapshotProperties().apply {
                stateSampleDuration = Duration.ZERO
            },
            groups = groups
        )

        val clusterLocal = ClusterState(
            ServicesState(
                serviceNameToInstances = concurrentMapOf(
                    "service" to ServiceInstances(
                        "service", setOf(
                            ServiceInstance(
                                id = "id",
                                tags = setOf("envoy"),
                                address = "127.0.0.3",
                                port = 4444
                            )
                        )
                    ),
                    "servicePresentInJustOneRemote" to ServiceInstances("servicePresentInJustOneRemote", setOf())
                )
            ),
            Locality.LOCAL, "cluster"
        ).toMultiClusterState()

        val remoteClusterWithBothServices = ClusterState(
            ServicesState(
                serviceNameToInstances = concurrentMapOf(
                    "service" to ServiceInstances("service", setOf()),
                    "servicePresentInJustOneRemote" to ServiceInstances("servicePresentInJustOneRemote", setOf())
                )
            ),
            Locality.REMOTE, "cluster2"
        ).toMultiClusterState()

        val remoteClusterWithJustOneService = ClusterState(
            ServicesState(
                serviceNameToInstances = concurrentMapOf(
                    "service" to ServiceInstances("service", setOf())
                )
            ),
            Locality.REMOTE, "cluster2"
        ).toMultiClusterState()

        // when
        val resultsRemoteWithBothServices = updater
            .services(
                Flux
                    .just(
                        clusterLocal,
                        remoteClusterWithBothServices
                    )
                    .delayElements(Duration.ofMillis(10))
            )
            .collectList().block()!!

        // when
        val resultsRemoteWithJustOneService = updater
            .services(
                Flux
                    .just(
                        clusterLocal,
                        remoteClusterWithJustOneService
                    )
                    .delayElements(Duration.ofMillis(10))
            )
            .collectList().block()!!

        // then
        resultsRemoteWithBothServices[0].adsSnapshot!!
            .hasTheSameClusters(resultsRemoteWithJustOneService[0].adsSnapshot!!)
            .hasTheSameEndpoints(resultsRemoteWithJustOneService[0].adsSnapshot!!)
            .hasTheSameSecuredClusters(resultsRemoteWithJustOneService[0].adsSnapshot!!)
        resultsRemoteWithBothServices[0].xdsSnapshot!!
            .hasTheSameClusters(resultsRemoteWithJustOneService[0].xdsSnapshot!!)
            .hasTheSameEndpoints(resultsRemoteWithJustOneService[0].xdsSnapshot!!)
            .hasTheSameSecuredClusters(resultsRemoteWithJustOneService[0].xdsSnapshot!!)
        resultsRemoteWithBothServices[1].adsSnapshot!!
            .hasTheSameClusters(resultsRemoteWithJustOneService[1].adsSnapshot!!)
            .hasTheSameEndpoints(resultsRemoteWithJustOneService[1].adsSnapshot!!)
            .hasTheSameSecuredClusters(resultsRemoteWithJustOneService[1].adsSnapshot!!)
        resultsRemoteWithBothServices[1].xdsSnapshot!!
            .hasTheSameClusters(resultsRemoteWithJustOneService[1].xdsSnapshot!!)
            .hasTheSameEndpoints(resultsRemoteWithJustOneService[1].xdsSnapshot!!)
            .hasTheSameSecuredClusters(resultsRemoteWithJustOneService[1].xdsSnapshot!!)
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
            ServicesState(serviceNameToInstances = concurrentMapOf()),
            Locality.LOCAL, "cluster"
        ).toMultiClusterState()

        // when
        val results = updater
            .services(
                Flux
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
            .hasAnEmptyLocalityEndpointsList("service", setOf("cluster"))
        results[1].xdsSnapshot!!
            .hasHttp2Cluster("service")
            .hasAnEmptyLocalityEndpointsList("service", setOf("cluster"))
    }

    @Test
    fun `should not include blacklisted services in wildcard dependencies but include in direct dependencies`() {
        // given
        val cache = MockCache()

        val allServicesGroup = AllServicesGroup(communicationMode = ADS)
        val groupWithBlacklistedDependency = groupOf(services = serviceDependencies("mock-service"))

        val groups = listOf(allServicesGroup, groupWithBlacklistedDependency)
        val domainsSuffixes = mutableListOf(".test.domain", ".domain2")

        val updater = snapshotUpdater(
            cache = cache,
            properties = SnapshotProperties().apply {
                outgoingPermissions.allServicesDependencies.notIncludedByPrefix = mutableSetOf(
                    "mock-", "regression-tests"
                )
                egress.domains = domainsSuffixes
            },
            groups = groups
        )

        val expectedWhitelistedServices = setOf("s1", "mockito", "s2", "frontend").toTypedArray()
        val expectedDomainsWhitelistedServices = setOf(
            "s1", "s1.test.domain", "s1.domain2", "mockito", "mockito.test.domain", "mockito.domain2",
            "s2", "s2.test.domain", "s2.domain2", "frontend", "frontend.test.domain", "frontend.domain2"
        ).toTypedArray()
        val expectedBlacklistedServices = setOf("mock-service").toTypedArray()
        val expectedDomainsBlacklistedServices =
            setOf("mock-service", "mock-service.test.domain", "mock-service.domain2").toTypedArray()

        // when
        updater.start(
            fluxOfServices(
                "s1", "mockito", "regression-tests", "s2", "frontend", "mock-service"
            )
        ).collectList().block()

        // then
        hasSnapshot(cache, allServicesGroup)
            .hasOnlyClustersFor(*expectedWhitelistedServices)
            .hasOnlyEndpointsFor(*expectedWhitelistedServices)
            .hasOnlyEgressRoutesForClusters(expected = *expectedDomainsWhitelistedServices)

        hasSnapshot(cache, groupWithBlacklistedDependency)
            .hasOnlyClustersFor(*expectedBlacklistedServices)
            .hasOnlyEndpointsFor(*expectedBlacklistedServices)
            .hasOnlyEgressRoutesForClusters(expected = *expectedDomainsBlacklistedServices)
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

    @Test
    fun `should generate group snapshots with tcpProxy`() {
        val cache = MockCache()

        val group = groupOf(
            services = serviceDependencies("existingService1"),
            domains = domainDependencies(
                "https://service-https.com",
                "https://service2-https.com",
                "http://service-http.com",
                "http://service2-http.com",
                "http://service-http-1337.com:1337",
                "http://service2-http-1337.com:1337",
                "https://service-https-1338.com:1338",
                "https://service2-https-1338.com:1338"
            ),
            listenerConfig = ListenersConfig(
                egressHost = "0.0.0.0",
                egressPort = 1234,
                ingressHost = "0.0.0.0",
                ingressPort = 1235,
                accessLogFilterSettings = AccessLogFilterSettings(null, AccessLogFiltersProperties()),
                useTransparentProxy = true
            )
        )

        cache.setSnapshot(group, uninitializedSnapshot)
        val updater = snapshotUpdater(
            cache = cache,
            groups = listOf(group)
        )

        // when
        updater.startWithServices("existingService1")

        // then
        hasSnapshot(cache, group)
            .hasOnlyClustersFor(
                "existingService1",
                "service-https_com_443",
                "service2-https_com_443",
                "service-http_com_80",
                "service2-http_com_80",
                "service-http-1337_com_1337",
                "service2-http-1337_com_1337",
                "service-https-1338_com_1338",
                "service2-https-1338_com_1338"
            )
        hasSnapshot(cache, group)
            .hasOnlyListenersFor(
                "ingress_listener",
                "egress_listener",
                "0.0.0.0:443",
                "0.0.0.0:1338",
                "0.0.0.0:1337",
                "0.0.0.0:80"
            )

        hasSnapshot(cache, group)
            .hasListener(
                "ingress_listener",
                1235
            )
        hasSnapshot(cache, group)
            .hasListener(
                "egress_listener",
                1234,
                filterMatchPort = 1234,
                useOriginalDst = true
            )
        hasSnapshot(cache, group)
            .hasListener(
                "0.0.0.0:443",
                443,
                isSslProxy = true,
                isVirtualListener = true,
                domains = setOf("service-https.com", "service2-https.com")
            )
        hasSnapshot(cache, group)
            .hasListener(
                "0.0.0.0:1337",
                1337,
                isSslProxy = false,
                isVirtualListener = true
            )
        hasSnapshot(cache, group)
            .hasListener(
                "0.0.0.0:1338",
                1338,
                isSslProxy = true,
                isVirtualListener = true,
                domains = setOf("service-https-1338.com", "service2-https-1338.com")
            )

        hasSnapshot(cache, group)
            .hasOnlyEgressRoutesForClusters()

        hasSnapshot(cache, group)
            .hasOnlyEgressRoutesForClusters(
                "1337",
                "service-http-1337.com:1337",
                "service2-http-1337.com:1337"
            )
        hasSnapshot(cache, group)
            .hasOnlyEgressRoutesForClusters(
                "80",
                "existingService1",
                "service-http.com",
                "service2-http.com"
            )
    }

    @Test
    fun `should generate group snapshots with tcpProxy and route for 80 when no domain defined`() {
        val cache = MockCache()

        val group = groupOf(
            services = serviceDependencies("existingService1"),
            domains = domainDependencies(
                "https://service-https.com",
                "https://service2-https.com",
                "https://service-https-1338.com:1338",
                "https://service2-https-1338.com:1338"
            ),
            listenerConfig = ListenersConfig(
                egressHost = "0.0.0.0",
                egressPort = 1234,
                ingressHost = "0.0.0.0",
                ingressPort = 1235,
                accessLogFilterSettings = AccessLogFilterSettings(null, AccessLogFiltersProperties()),
                useTransparentProxy = true
            )
        )

        cache.setSnapshot(group, uninitializedSnapshot)
        val updater = snapshotUpdater(
            cache = cache,
            groups = listOf(group)
        )

        // when
        updater.startWithServices("existingService1")

        // then
        hasSnapshot(cache, group)
            .hasOnlyClustersFor(
                "existingService1",
                "service-https_com_443",
                "service2-https_com_443",
                "service-https-1338_com_1338",
                "service2-https-1338_com_1338"
            )
        hasSnapshot(cache, group)
            .hasOnlyListenersFor(
                "ingress_listener",
                "egress_listener",
                "0.0.0.0:443",
                "0.0.0.0:1338",
                "0.0.0.0:80"
            )

        hasSnapshot(cache, group)
            .hasListener(
                "ingress_listener",
                1235
            )
        hasSnapshot(cache, group)
            .hasListener(
                "egress_listener",
                1234,
                filterMatchPort = 1234,
                useOriginalDst = true
            )
        hasSnapshot(cache, group)
            .hasListener(
                "0.0.0.0:443",
                443,
                isSslProxy = true,
                isVirtualListener = true,
                domains = setOf("service-https.com", "service2-https.com")
            )
        hasSnapshot(cache, group)
            .hasListener(
                "0.0.0.0:1338",
                1338,
                isSslProxy = true,
                isVirtualListener = true,
                domains = setOf("service-https-1338.com", "service2-https-1338.com")
            )

        hasSnapshot(cache, group)
            .hasOnlyEgressRoutesForClusters()

        hasSnapshot(cache, group)
            .hasOnlyEgressRoutesForClusters(
                "80",
                "existingService1"
            )
    }

    @Test
    fun `should generate group snapshots when tcpProxy not enabled`() {
        val cache = MockCache()

        val group = groupOf(
            services = serviceDependencies("existingService1"),
            domains = domainDependencies(
                "https://service-https.com",
                "https://service2-https.com",
                "http://service-http.com",
                "http://service2-http.com",
                "http://service-http-1337.com:1337",
                "http://service2-http-1337.com:1337",
                "https://service-https-1338.com:1338",
                "https://service2-https-1338.com:1338"
            ),
            listenerConfig = ListenersConfig(
                egressHost = "0.0.0.0",
                egressPort = 1234,
                ingressHost = "0.0.0.0",
                ingressPort = 1235,
                accessLogFilterSettings = AccessLogFilterSettings(null, AccessLogFiltersProperties()),
                useTransparentProxy = false
            )
        )

        cache.setSnapshot(group, uninitializedSnapshot)
        val updater = snapshotUpdater(
            cache = cache,
            groups = listOf(group)
        )

        // when
        updater.startWithServices("existingService1")

        // then
        hasSnapshot(cache, group)
            .hasOnlyClustersFor(
                "existingService1",
                "service-https_com_443",
                "service2-https_com_443",
                "service-http_com_80",
                "service2-http_com_80",
                "service-http-1337_com_1337",
                "service2-http-1337_com_1337",
                "service-https-1338_com_1338",
                "service2-https-1338_com_1338"
            )
        hasSnapshot(cache, group)
            .hasOnlyListenersFor(
                "ingress_listener",
                "egress_listener"
            )

        hasSnapshot(cache, group)
            .hasListener(
                "ingress_listener",
                1235
            )

        hasSnapshot(cache, group)
            .hasOnlyEgressRoutesForClusters(
                "default_routes",
                "existingService1",
                "service-https.com",
                "service2-https.com",
                "service-http.com",
                "service2-http.com",
                "service-http-1337.com:1337",
                "service2-http-1337.com:1337",
                "service-https-1338.com:1338",
                "service2-https-1338.com:1338"
            )
    }

    private fun SnapshotUpdater.startWithServices(vararg services: Array<String>) {
        this.start(fluxOfServices(*services)).collectList().block()
    }

    private fun SnapshotUpdater.startWithServices(vararg services: String) {
        this.start(fluxOfServices(*services)).blockFirst()
    }

    private fun fluxOfServices(vararg services: Array<String>) = Flux
        .just(*services.map { createClusterState(*it).toMultiClusterState() }.toTypedArray())
        .delayElements(Duration.ofMillis(500))

    private fun fluxOfServices(vararg services: String) = Flux.just(createClusterState(*services).toMultiClusterState())
    private fun createClusterState(vararg services: String) = ClusterState(
        ServicesState(
            serviceNameToInstances = ConcurrentHashMap(services.associateWith { ServiceInstances(it, emptySet()) })

        ),
        Locality.LOCAL, "cluster"
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

        override fun createDeltaWatch(
            request: DeltaXdsRequest?,
            requesterVersion: String?,
            resourceVersions: MutableMap<String, String>?,
            pendingResources: MutableSet<String>?,
            isWildcard: Boolean,
            responseConsumer: Consumer<DeltaResponse>?,
            hasClusterChanged: Boolean
        ): DeltaWatch {
            throw UnsupportedOperationException("not used in testing")
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

    private fun Snapshot.hasOnlyListenersFor(vararg expected: String): Snapshot {
        assertThat(this.resources(Resources.ResourceType.LISTENER).keys.toSet())
            .isEqualTo(expected.toSet())
        return this
    }

    private fun Snapshot.hasListener(
        listenerName: String,
        listenerSocketPort: Int,
        listenerSocketAddress: String = "0.0.0.0",
        filterMatchPort: Int? = null,
        useOriginalDst: Boolean = false,
        isSslProxy: Boolean = false,
        isVirtualListener: Boolean = false,
        domains: Set<String> = emptySet()
    ): Snapshot {
        val listener: Listener? = this.listeners().resources()[listenerName]
        assertThat(listener).isNotNull
        assertThat(listener!!.address.socketAddress.address).isEqualTo(listenerSocketAddress)
        assertThat(listener.address.socketAddress.portValue).isEqualTo(listenerSocketPort)
        if (useOriginalDst) {
            assertThat(listener.useOriginalDst.value).isEqualTo(useOriginalDst)
        }
        if (isVirtualListener) {
            assertThat(listener.deprecatedV1.bindToPort.value).isEqualTo(false)
        }
        listener.filterChainsList.forEach {
            if (isSslProxy) {
                assertThat(it.filterChainMatch.transportProtocol).isEqualTo("tls")
                assertThat(it.filterChainMatch.serverNamesList).containsAnyElementsOf(domains)
                assertThat(it.filtersList.size).isEqualTo(1)
                assertThat(it.filtersList[0].name).isEqualTo("envoy.tcp_proxy")
            } else if (filterMatchPort != null) {
                assertThat(it.filterChainMatch.destinationPort.value).isEqualTo(filterMatchPort)
            } else {
                assertThat(it.filterChainMatch.transportProtocol).isEqualTo("raw_buffer")
                assertThat(it.filtersList.size).isEqualTo(1)
                assertThat(it.filtersList[0].name).isEqualTo("envoy.filters.network.http_connection_manager")
            }
        }

        return this
    }

    private fun Snapshot.hasOnlyEndpointsFor(vararg expected: String): Snapshot {
        assertThat(this.resources(Resources.ResourceType.ENDPOINT).keys.toSet())
            .isEqualTo(expected.toSet())
        return this
    }

    private fun Snapshot.hasOnlyEgressRoutesForClusters(
        routeName: String = "default_routes",
        vararg expected: String
    ): Snapshot {
        val routes = this.resources(Resources.ResourceType.ROUTE)[routeName] as RouteConfiguration
        assertThat(routes.virtualHostsList.flatMap { it.domainsList }.toSet())
            .isEqualTo(expected.toSet() + setOf("envoy-original-destination", "*"))
        return this
    }

    private fun Snapshot.withoutClusters() {
        assertThat(this.resources(Resources.ResourceType.CLUSTER).keys).isEmpty()
    }

    private fun Snapshot.hasVirtualHostConfig(name: String, idleTimeout: String, requestTimeout: String): Snapshot {
        val routeAction = this.routes()
            .resources()["default_routes"]!!.virtualHostsList.singleOrNull { it.name == name }?.routesList?.map { it.route }
            ?.firstOrNull()
        assertThat(routeAction).overridingErrorMessage("Expecting virtualHost for $name").isNotNull
        assertThat(routeAction?.timeout).isEqualTo(Durations.parse(requestTimeout))
        assertThat(routeAction?.idleTimeout).isEqualTo(Durations.parse(idleTimeout))
        return this
    }

    private fun Snapshot.hasRetryPolicySpecified(name: String, retryPolicy: RetryPolicy?): Snapshot {
        val routeAction = this.routes()
            .resources()["default_routes"]!!.virtualHostsList.singleOrNull { it.name == name }?.routesList?.map { it.route }
            ?.firstOrNull()
        retryPolicy?.also {
            assertThat(routeAction?.retryPolicy).isEqualTo(retryPolicy)
        }
        return this
    }

    private fun groupOf(
        services: Set<ServiceDependency> = emptySet(),
        domains: Set<DomainDependency> = emptySet(),
        listenerConfig: ListenersConfig? = null
    ) = ServicesGroup(
        communicationMode = XDS,
        listenersConfig = listenerConfig,
        proxySettings = ProxySettings().with(
            serviceDependencies = services, domainDependencies = domains
        )
    )

    private fun GlobalSnapshot.hasHttp2Cluster(clusterName: String): GlobalSnapshot {
        val cluster = this.clusters[clusterName]
        assertThat(cluster).isNotNull
        assertThat(cluster!!.hasHttp2ProtocolOptions()).isTrue()
        return this
    }

    private fun GlobalSnapshot.hasAnEmptyLocalityEndpointsList(
        clusterName: String,
        zones: Set<String>
    ): GlobalSnapshot {
        val endpoints = this.endpoints[clusterName]
        assertThat(endpoints).isNotNull
        assertThat(endpoints!!.endpointsList.map { it.locality.zone }.toSet()).containsAll(zones)
        assertThat(endpoints.endpointsList.flatMap { it.lbEndpointsList }).isEmpty()
        return this
    }

    private fun GlobalSnapshot.hasAnEndpoint(clusterName: String, ip: String, port: Int): GlobalSnapshot {
        val endpoints = this.endpoints[clusterName]
        assertThat(endpoints).isNotNull
        assertThat(endpoints!!.endpointsList.flatMap { it.lbEndpointsList })
            .anyMatch { it.endpoint.address.socketAddress.let { it.address == ip && it.portValue == port } }
        return this
    }

    private fun GlobalSnapshot.hasTheSameClusters(other: GlobalSnapshot): GlobalSnapshot {
        val clusters = this.clusters
        assertThat(clusters).isEqualTo(other.clusters)
        return this
    }

    private fun GlobalSnapshot.hasTheSameEndpoints(other: GlobalSnapshot): GlobalSnapshot {
        val endpoints = this.endpoints
        assertThat(endpoints).isEqualTo(other.endpoints)
        return this
    }

    private fun GlobalSnapshot.hasTheSameSecuredClusters(other: GlobalSnapshot): GlobalSnapshot {
        val securedClusters = this.securedClusters
        assertThat(securedClusters).isEqualTo(other.securedClusters)
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
        groupSnapshotScheduler: ParallelizableScheduler = DirectScheduler,
        snapshotChangeAuditor: SnapshotChangeAuditor = NoopSnapshotChangeAuditor
    ) = SnapshotUpdater(
        cache = cache,
        properties = properties,
        snapshotFactory = snapshotFactory(properties, simpleMeterRegistry),
        globalSnapshotScheduler = Schedulers.newSingle("update-snapshot"),
        groupSnapshotScheduler = groupSnapshotScheduler,
        onGroupAdded = Flux.just(groups),
        meterRegistry = simpleMeterRegistry,
        versions = SnapshotsVersions(),
        snapshotChangeAuditor = snapshotChangeAuditor,
        globalSnapshotAuditScheduler = Schedulers.newSingle("audit-snapshot")
    )

    private fun concurrentMapOf(vararg elements: Pair<ServiceName, ServiceInstances>): ConcurrentHashMap<ServiceName, ServiceInstances> {
        val state = ConcurrentHashMap<ServiceName, ServiceInstances>()
        elements.forEach { (name, instance) -> state[name] = instance }
        return state
    }
}

private fun Snapshot.allClustersHasConfiguredTap() {
    assertThat(this.resources(Resources.ResourceType.CLUSTER).values.map { it as Cluster }
        .map { it.transportSocket.name }
        .all { it == "envoy.transport_sockets.tap" })
}

private fun Snapshot.allClustersHasNotConfiguredTap() {
    assertThat(!this.resources(Resources.ResourceType.CLUSTER).values.map { it as Cluster }
        .map { it.transportSocket.name }
        .all { it == "envoy.transport_sockets.tap" })
}

fun serviceDependencies(vararg dependencies: Pair<String, Outgoing.TimeoutPolicy?>): Set<ServiceDependency> =
    dependencies.map {
        ServiceDependency(
            service = it.first,
            settings = DependencySettings(
                timeoutPolicy = it.second ?: Outgoing.TimeoutPolicy()
            )
        )
    }.toSet()

fun serviceDependencies(vararg serviceNames: String): Set<ServiceDependency> =
    serviceNames.map {
        ServiceDependency(
            service = it,
            settings = DependencySettings(
                timeoutPolicy = outgoingTimeoutPolicy(),
                retryPolicy = pl.allegro.tech.servicemesh.envoycontrol.groups.RetryPolicy(
                    hostSelectionRetryMaxAttempts = 3,
                    retryHostPredicate = listOf(RetryHostPredicate.PREVIOUS_HOSTS),
                    numberRetries = 1,
                    retryBackOff = RetryBackOff(Durations.fromMillis(25), Durations.fromMillis(250)),
                    rateLimitedRetryBackOff = EnvoyControlRateLimitedRetryBackOff(
                        listOf(
                            EnvoyControlResetHeader("Retry-After", "SECONDS")
                        )
                    )
                )
            )
        )
    }.toSet()

fun outgoingTimeoutPolicy(
    idleTimeout: Long = 120L,
    connectionIdleTimeout: Long = 120L,
    requestTimeout: Long = 120L
) = Outgoing.TimeoutPolicy(
    idleTimeout = Durations.fromSeconds(idleTimeout),
    connectionIdleTimeout = Durations.fromSeconds(connectionIdleTimeout),
    requestTimeout = Durations.fromSeconds(requestTimeout)
)

fun domainDependencies(vararg serviceNames: String): Set<DomainDependency> =
    serviceNames.map {
        DomainDependency(
            domain = it,
            settings = DependencySettings(
                timeoutPolicy = Outgoing.TimeoutPolicy(
                    idleTimeout = Durations.fromSeconds(120L),
                    requestTimeout = Durations.fromSeconds(120L)
                )
            )
        )
    }.toSet()
