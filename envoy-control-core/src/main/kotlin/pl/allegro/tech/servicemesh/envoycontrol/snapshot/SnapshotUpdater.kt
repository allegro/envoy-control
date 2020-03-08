package pl.allegro.tech.servicemesh.envoycontrol.snapshot

import io.envoyproxy.controlplane.cache.SnapshotCache
import io.micrometer.core.instrument.MeterRegistry
import pl.allegro.tech.servicemesh.envoycontrol.groups.CommunicationMode.ADS
import pl.allegro.tech.servicemesh.envoycontrol.groups.CommunicationMode.XDS
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group
import pl.allegro.tech.servicemesh.envoycontrol.logger
import pl.allegro.tech.servicemesh.envoycontrol.services.LocalityAwareServicesState
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.listeners.EnvoyListenersFactory
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.listeners.filters.EnvoyHttpFilters
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.routing.ServiceTagMetadataGenerator
import pl.allegro.tech.servicemesh.envoycontrol.utils.measureBuffer
import pl.allegro.tech.servicemesh.envoycontrol.utils.onBackpressureLatestMeasured
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler

class SnapshotUpdater(
    private val cache: SnapshotCache<Group>,
    private val properties: SnapshotProperties,
    private val scheduler: Scheduler,
    private val onGroupAdded: Flux<out List<Group>>,
    private val meterRegistry: MeterRegistry,
    envoyHttpFilters: EnvoyHttpFilters = EnvoyHttpFilters.emptyFilters,
    serviceTagFilter: ServiceTagMetadataGenerator = ServiceTagMetadataGenerator(properties.routing.serviceTags)
) {
    companion object {
        private val logger by logger()
    }

    private val versions = SnapshotsVersions()
    private val snapshotFactory = EnvoySnapshotFactory(
        ingressRoutesFactory = EnvoyIngressRoutesFactory(properties),
        egressRoutesFactory = EnvoyEgressRoutesFactory(properties),
        clustersFactory = EnvoyClustersFactory(properties),
        listenersFactory = EnvoyListenersFactory(
                properties,
                envoyHttpFilters
        ),
        // Remember when LDS change we have to send RDS again
        snapshotsVersions = versions,
        properties = properties,
        meterRegistry = meterRegistry,
        serviceTagFilter = serviceTagFilter
    )

    private var globalSnapshot: UpdateResult? = null

    fun getGlobalSnapshot(): UpdateResult? {
        return globalSnapshot
    }

    fun start(changes: Flux<List<LocalityAwareServicesState>>): Flux<UpdateResult> {
        return Flux.merge(
                1, // prefetch 1, instead of default 32, to avoid processing stale items in case of backpressure
                services(changes).subscribeOn(scheduler),
                groups().subscribeOn(scheduler)
        )
                .measureBuffer("snapshot-updater-merged", meterRegistry, innerSources = 2)
                .checkpoint("snapshot-updater-merged")
                .name("snapshot-updater-merged").metrics()
                .scan { previous: UpdateResult, newUpdate: UpdateResult ->
                    UpdateResult(
                            action = newUpdate.action,
                            groups = newUpdate.groups,
                            adsSnapshot = newUpdate.adsSnapshot ?: previous.adsSnapshot,
                            xdsSnapshot = newUpdate.xdsSnapshot ?: previous.xdsSnapshot
                    )
                }
                .doOnNext { result ->
                    val groups = if (result.action == Action.ALL_SERVICES_GROUP_ADDED) {
                        cache.groups()
                    } else {
                        result.groups
                    }

                    if (result.adsSnapshot != null || result.xdsSnapshot != null) {
                        updateSnapshotForGroups(groups, result)
                    }
                }
    }

    fun groups(): Flux<UpdateResult> {
        // see GroupChangeWatcher
        return onGroupAdded
                .publishOn(scheduler)
                .measureBuffer("snapshot-updater-groups-published", meterRegistry)
                .checkpoint("snapshot-updater-groups-published")
                .name("snapshot-updater-groups-published").metrics()
                .map { groups ->
                    UpdateResult(action = Action.SERVICES_GROUP_ADDED, groups = groups)
                }
                .onErrorResume { e ->
                    meterRegistry.counter("snapshot-updater.groups.updates.errors").increment()
                    logger.error("Unable to process new group", e)
                    Mono.justOrEmpty(UpdateResult(action = Action.ERROR_PROCESSING_CHANGES))
                }
    }

    fun services(changes: Flux<List<LocalityAwareServicesState>>): Flux<UpdateResult> {
        return changes
                .sample(properties.stateSampleDuration)
                .name("snapshot-updater-services-sampled").metrics()
                .onBackpressureLatestMeasured("snapshot-updater-services-sampled", meterRegistry)
                // prefetch = 1, instead of default 256, to avoid processing stale states in case of backpressure
                .publishOn(scheduler, 1)
                .measureBuffer("snapshot-updater-services-published", meterRegistry)
                .checkpoint("snapshot-updater-services-published")
                .name("snapshot-updater-services-published").metrics()
                .map { states ->
                    var lastXdsSnapshot: GlobalSnapshot? = null
                    var lastAdsSnapshot: GlobalSnapshot? = null

                    if (properties.enabledCommunicationModes.xds) {
                        lastXdsSnapshot = snapshotFactory.newSnapshot(states, XDS)
                    }
                    if (properties.enabledCommunicationModes.ads) {
                        lastAdsSnapshot = snapshotFactory.newSnapshot(states, ADS)
                    }

                    val updateResult = UpdateResult(
                            action = Action.ALL_SERVICES_GROUP_ADDED,
                            adsSnapshot = lastAdsSnapshot,
                            xdsSnapshot = lastXdsSnapshot
                    )
                    globalSnapshot = updateResult
                    updateResult
                }
                .onErrorResume { e ->
                    meterRegistry.counter("snapshot-updater.services.updates.errors").increment()
                    logger.error("Unable to process service changes", e)
                    Mono.justOrEmpty(UpdateResult(action = Action.ERROR_PROCESSING_CHANGES))
                }
    }

    fun updateSnapshotForGroup(group: Group, globalSnapshot: GlobalSnapshot) {
        try {
            val groupSnapshot = snapshotFactory.getSnapshotForGroup(group, globalSnapshot)
            cache.setSnapshot(group, groupSnapshot)
        } catch (e: Throwable) {
            meterRegistry.counter("snapshot-updater.services.${group.serviceName}.updates.errors").increment()
            logger.error("Unable to create snapshot for group ${group.serviceName}", e)
        }
    }

    private fun updateSnapshotForGroups(groups: Collection<Group>, result: UpdateResult) {
        versions.retainGroups(cache.groups())
        groups.forEach { group ->
            if (result.adsSnapshot != null && group.communicationMode == ADS) {
                updateSnapshotForGroup(group, result.adsSnapshot)
            } else if (result.xdsSnapshot != null && group.communicationMode == XDS) {
                updateSnapshotForGroup(group, result.xdsSnapshot)
            } else {
                meterRegistry.counter("snapshot-updater.communication-mode.errors").increment()
                logger.error("Requested snapshot for ${group.communicationMode.name} mode, but it is not here. " +
                    "Handling Envoy with not supported communication mode should have been rejected before." +
                    " Please report this to EC developers.")
            }
        }
    }
}

enum class Action {
    SERVICES_GROUP_ADDED, ALL_SERVICES_GROUP_ADDED, ERROR_PROCESSING_CHANGES
}

class UpdateResult(
    val action: Action,
    val groups: List<Group> = listOf(),
    val adsSnapshot: GlobalSnapshot? = null,
    val xdsSnapshot: GlobalSnapshot? = null
)
