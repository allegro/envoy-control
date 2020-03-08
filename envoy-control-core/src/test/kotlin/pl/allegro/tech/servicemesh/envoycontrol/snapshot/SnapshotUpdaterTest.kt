package pl.allegro.tech.servicemesh.envoycontrol.snapshot

import com.google.protobuf.util.Durations
import io.envoyproxy.controlplane.cache.Response
import io.envoyproxy.controlplane.cache.Snapshot
import io.envoyproxy.controlplane.cache.SnapshotCache
import io.envoyproxy.controlplane.cache.StatusInfo
import io.envoyproxy.controlplane.cache.Watch
import io.envoyproxy.envoy.api.v2.DiscoveryRequest
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
import pl.allegro.tech.servicemesh.envoycontrol.groups.Incoming
import pl.allegro.tech.servicemesh.envoycontrol.groups.IncomingEndpoint
import pl.allegro.tech.servicemesh.envoycontrol.groups.Outgoing
import pl.allegro.tech.servicemesh.envoycontrol.groups.ProxySettings
import pl.allegro.tech.servicemesh.envoycontrol.groups.ServiceDependency
import pl.allegro.tech.servicemesh.envoycontrol.groups.ServicesGroup
import pl.allegro.tech.servicemesh.envoycontrol.groups.with
import pl.allegro.tech.servicemesh.envoycontrol.services.Locality
import pl.allegro.tech.servicemesh.envoycontrol.services.LocalityAwareServicesState
import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceInstances
import pl.allegro.tech.servicemesh.envoycontrol.services.ServicesState
import reactor.core.publisher.Flux
import reactor.core.scheduler.Schedulers
import java.util.function.Consumer

class SnapshotUpdaterTest {

    companion object {
        @JvmStatic
        fun configurationModeNotSupported() = listOf(
            Arguments.of(false, false, ADS, "ADS not supported by server"),
            Arguments.of(false, false, XDS, "XDS not supported by server")
        )
    }

    val proxySettings = ProxySettings(
        incoming = Incoming(
            endpoints = listOf(IncomingEndpoint(path = "/endpoint", clients = setOf("client"))),
            permissionsEnabled = true
        )
    )
    val groupWithProxy = AllServicesGroup(
        communicationMode = ADS,
        serviceName = "service",
        proxySettings = proxySettings
    )
    val groupWithServiceName = groupOf(
        services = setOf(ServiceDependency(service = "existingService2"))
    ).copy(serviceName = "ipsum-service")

    val simpleMeterRegistry = SimpleMeterRegistry()

    @Test
    fun `should generate group snapshots`() {
        val cache = newCache()
        val uninitializedSnapshot = null

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

        val updater = SnapshotUpdater(
            cache,
            properties = SnapshotProperties().apply {
                incomingPermissions.enabled = true
            },
            scheduler = Schedulers.newSingle("update-snapshot"),
            onGroupAdded = Flux.just(groups),
            meterRegistry = simpleMeterRegistry
        )

        // when
        updater.startWithServices("existingService1", "existingService2")

        // then
        hasSnapshot(cache, AllServicesGroup(communicationMode = XDS))
            .hasClusters("existingService1", "existingService2")

        hasSnapshot(cache, groupWithProxy)
            .hasClusters("existingService1", "existingService2")
            .hasSecuredIngressRoute("/endpoint", "client")
            .hasServiceNameRequestHeader("service")

        hasSnapshot(cache, groupOf(services = serviceDependencies("existingService1")))
            .hasClusters("existingService1")

        hasSnapshot(cache, groupOf(services = serviceDependencies("existingService2")))
            .hasClusters("existingService2")

        hasSnapshot(cache, groupWithServiceName)
            .hasClusters("existingService2")
            .hasServiceNameRequestHeader("ipsum-service")

        hasSnapshot(cache, groupOf(
            services = serviceDependencies("existingService1", "existingService2"),
            domains = domainDependencies("http://domain")
        )).hasClusters("existingService1", "existingService2", "domain_80")

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

        val uninitializedSnapshot = null
        val cache = newCache()
        cache.setSnapshot(allServiceGroup, uninitializedSnapshot)

        val updater = SnapshotUpdater(
            cache,
            properties = SnapshotProperties().apply {
                enabledCommunicationModes.ads = adsSupported; enabledCommunicationModes.xds = xdsSupported
            },
            scheduler = Schedulers.newSingle("update-snapshot"),
            onGroupAdded = Flux.just(listOf()),
            meterRegistry = simpleMeterRegistry
        )

        // when
        updater.start(
            Flux.just(emptyList())
        ).blockFirst()

        // should not generate snapshot
        assertThat(cache.getSnapshot(allServiceGroup)).isNull()
    }

    @Test
    fun `should generate snapshot with empty version and one route`() {
        // given
        val emptyGroup = groupOf()

        val uninitializedSnapshot = null
        val cache = newCache()
        cache.setSnapshot(emptyGroup, uninitializedSnapshot)

        val updater = SnapshotUpdater(
            cache,
            properties = SnapshotProperties(),
            scheduler = Schedulers.newSingle("update-snapshot"),
            onGroupAdded = Flux.just(listOf()),
            meterRegistry = simpleMeterRegistry
        )

        // when
        updater.start(
            Flux.just(emptyList())
        ).blockFirst()

        // then version is set to empty
        val snapshot = hasSnapshot(cache, emptyGroup)
        assertThat(snapshot.endpoints().version()).isEqualTo(EndpointsVersion.EMPTY_VERSION.value)
        assertThat(snapshot.clusters().version()).isEqualTo(ClustersVersion.EMPTY_VERSION.value)
        assertThat(snapshot.listeners().version()).isEqualTo(ListenersVersion.EMPTY_VERSION.value)
        assertThat(snapshot.routes().version()).isEqualTo(RoutesVersion.EMPTY_VERSION.value)

        assertThat(snapshot.routes().resources().values).hasSize(2)
        // two fallbacks: proxying direct IP requests and 503 for missing services
        assertThat(snapshot.routes().resources().values
            .first { it.name == "default_routes" }.virtualHostsCount)
            .isEqualTo(2)
    }

    @Test
    fun `should not crash on bad snapshot generation`() {
        // given
        val servicesGroup = servicesGroupWithAnError("example-service")
        val cache = newCache()
        val globalSnapshot = GlobalSnapshot(listOf(), listOf())
        cache.setSnapshot(servicesGroup, null)
        val updater = SnapshotUpdater(
            cache,
            properties = SnapshotProperties(),
            scheduler = Schedulers.newSingle("update-snapshot"),
            onGroupAdded = Flux.just(),
            meterRegistry = simpleMeterRegistry
        )

        // when
        updater.updateSnapshotForGroup(servicesGroup, globalSnapshot)

        // then
        val snapshot = cache.getSnapshot(servicesGroup)
        assertThat(snapshot).isEqualTo(null)
        assertThat(simpleMeterRegistry.find("snapshot-updater.services.example-service.updates.errors")
            .counter()?.count()).isEqualTo(1.0)
    }

    private fun servicesGroupWithAnError(name: String): ServicesGroup {
        val proxySettings = ProxySettings(
            incoming = Incoming(
                endpoints = listOf(
                    IncomingEndpoint(
                        methods = setOf("INVALID")
                    )
                ),
                permissionsEnabled = true
            )
        )
        return ServicesGroup(ADS, name, proxySettings)
    }

    private fun SnapshotUpdater.startWithServices(vararg services: String) {
        this.start(
            Flux.just(
                listOf(
                    LocalityAwareServicesState(
                        ServicesState(
                            serviceNameToInstances = services.map { it to ServiceInstances(it, emptySet()) }.toMap()

                        ),
                        Locality.LOCAL, "zone"
                    )
                )
            )
        ).blockFirst()
    }

    private fun newCache(): SnapshotCache<Group> {
        return object : SnapshotCache<Group> {

            val groups: MutableMap<Group, Snapshot?> = mutableMapOf()

            override fun groups(): MutableCollection<Group> {
                return groups.keys.toMutableList()
            }

            override fun getSnapshot(group: Group): Snapshot? {
                return groups[group]
            }

            override fun setSnapshot(group: Group, snapshot: Snapshot?) {
                groups[group] = snapshot
            }

            override fun statusInfo(group: Group): StatusInfo<Group> {
                throw UnsupportedOperationException("not used in testing")
            }

            override fun createWatch(
                ads: Boolean,
                request: DiscoveryRequest,
                knownResourceNames: MutableSet<String>,
                responseConsumer: Consumer<Response>
            ): Watch {
                throw UnsupportedOperationException("not used in testing")
            }

            override fun clearSnapshot(group: Group?): Boolean {
                return false
            }
        }
    }

    private fun hasSnapshot(cache: SnapshotCache<Group>, group: Group): Snapshot {
        val snapshot = cache.getSnapshot(group)
        assertThat(snapshot).isNotNull
        return snapshot
    }

    private fun Snapshot.hasClusters(vararg expected: String): Snapshot {
        assertThat(this.clusters().resources().keys.toSet())
            .isEqualTo(expected.toSet())
        return this
    }

    private fun Snapshot.hasSecuredIngressRoute(endpoint: String, client: String): Snapshot {
        assertThat(this.routes().resources().getValue("ingress_secured_routes").virtualHostsList.first().routesList
            .map { it.match }
            .filter { it.path == endpoint }
            .filter {
                it.headersList
                    .any { it.name == "x-service-name" && it.exactMatch == client }
            }
        ).isNotEmpty
        return this
    }

    private fun Snapshot.hasServiceNameRequestHeader(serviceName: String): Snapshot {
        assertThat(this.routes().resources().getValue("default_routes").requestHeadersToAddList
            .map { it.header }
            .filter { it.key == "x-service-name" && it.value == serviceName }
        ).hasSize(1)
        return this
    }

    private fun Snapshot.withoutClusters() {
        assertThat(this.clusters().resources().keys).isEmpty()
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
