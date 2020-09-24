package pl.allegro.tech.servicemesh.envoycontrol.snapshot

import io.envoyproxy.controlplane.cache.SnapshotCache
import io.envoyproxy.controlplane.cache.v2.Snapshot
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import pl.allegro.tech.servicemesh.envoycontrol.groups.CommunicationMode.ADS
import pl.allegro.tech.servicemesh.envoycontrol.groups.CommunicationMode.XDS
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group
import pl.allegro.tech.servicemesh.envoycontrol.logger
import pl.allegro.tech.servicemesh.envoycontrol.services.MultiClusterState
import pl.allegro.tech.servicemesh.envoycontrol.utils.ParallelizableScheduler
import pl.allegro.tech.servicemesh.envoycontrol.utils.doOnNextScheduledOn
import pl.allegro.tech.servicemesh.envoycontrol.utils.measureBuffer
import pl.allegro.tech.servicemesh.envoycontrol.utils.noopTimer
import pl.allegro.tech.servicemesh.envoycontrol.utils.onBackpressureLatestMeasured
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler

class SnapshotUpdater(
    private val cache: SnapshotCache<Group, Snapshot>,
    private val properties: SnapshotProperties,
    private val snapshotFactory: EnvoySnapshotFactory,
    private val globalSnapshotScheduler: Scheduler,
    private val groupSnapshotScheduler: ParallelizableScheduler,
    private val onGroupAdded: Flux<out List<Group>>,
    private val meterRegistry: MeterRegistry
) {
    companion object {
        private val logger by logger()
    }

    private val versions = SnapshotsVersions()

    private var globalSnapshot: UpdateResult? = null

    fun getGlobalSnapshot(): UpdateResult? {
        return globalSnapshot
    }

    fun start(states: Flux<MultiClusterState>): Flux<UpdateResult> {
        return Flux.merge(
                1, // prefetch 1, instead of default 32, to avoid processing stale items in case of backpressure
                // step 1: creates cluster configuration for global snapshot
                services(states).subscribeOn(globalSnapshotScheduler),
                // step 2: only watches groups. if groups change we use the last services state and update those groups
                groups().subscribeOn(globalSnapshotScheduler)
        )
                .measureBuffer("snapshot-updater-merged", meterRegistry, innerSources = 2)
                .checkpoint("snapshot-updater-merged")
                .name("snapshot-updater-merged").metrics()
                // step 3: group updates don't provide a snapshot,
                // so we piggyback the last updated snapshot state for use
                .scan { previous: UpdateResult, newUpdate: UpdateResult ->
                    UpdateResult(
                            action = newUpdate.action,
                            groups = newUpdate.groups,
                            adsSnapshot = newUpdate.adsSnapshot ?: previous.adsSnapshot,
                            xdsSnapshot = newUpdate.xdsSnapshot ?: previous.xdsSnapshot
                    )
                }
                // concat map guarantees sequential processing (unlike flatMap)
                .concatMap { result ->
                    val groups = if (result.action == Action.ALL_SERVICES_GROUP_ADDED) {
                        cache.groups()
                    } else {
                        result.groups
                    }

                    // step 4: update the snapshot for either all groups (if services changed)
                    //         or specific groups (groups changed).
                    // TODO(dj): on what occasion can this be false?
                    if (result.adsSnapshot != null || result.xdsSnapshot != null) {
                        // Stateful operation! This is the meat of this processing.
                        updateSnapshotForGroups(groups, result)
                    } else {
                        Mono.empty()
                    }
                }
    }

    internal fun groups(): Flux<UpdateResult> {
        // see GroupChangeWatcher
        return onGroupAdded
                .publishOn(globalSnapshotScheduler)
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

    internal fun services(states: Flux<MultiClusterState>): Flux<UpdateResult> {
        return states
                .sample(properties.stateSampleDuration)
                .name("snapshot-updater-services-sampled").metrics()
                .onBackpressureLatestMeasured("snapshot-updater-services-sampled", meterRegistry)
                // prefetch = 1, instead of default 256, to avoid processing stale states in case of backpressure
                .publishOn(globalSnapshotScheduler, 1)
                .measureBuffer("snapshot-updater-services-published", meterRegistry)
                .checkpoint("snapshot-updater-services-published")
                .name("snapshot-updater-services-published").metrics()
                .createClusterConfigurations()
                .map { (states, clusters) ->
                    var lastXdsSnapshot: GlobalSnapshot? = null
                    var lastAdsSnapshot: GlobalSnapshot? = null

                    if (properties.enabledCommunicationModes.xds) {
                        lastXdsSnapshot = snapshotFactory.newSnapshot(states, clusters, XDS)
                    }
                    if (properties.enabledCommunicationModes.ads) {
                        lastAdsSnapshot = snapshotFactory.newSnapshot(states, clusters, ADS)
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

    private fun snapshotTimer(serviceName: String) = if (properties.metrics.cacheSetSnapshot) {
        meterRegistry.timer("snapshot-updater.set-snapshot.$serviceName.time")
    } else {
        noopTimer
    }

    private fun updateSnapshotForGroup(group: Group, globalSnapshot: GlobalSnapshot) {
        try {
            val groupSnapshot = snapshotFactory.getSnapshotForGroup(group, globalSnapshot)
            snapshotTimer(group.serviceName).record {
                cache.setSnapshot(group, groupSnapshot)
            }
        } catch (e: Throwable) {
            meterRegistry.counter("snapshot-updater.services.${group.serviceName}.updates.errors").increment()
            logger.error("Unable to create snapshot for group ${group.serviceName}", e)
        }
    }

    private val updateSnapshotForGroupsTimer = meterRegistry.timer("snapshot-updater.update-snapshot-for-groups.time")

    private fun updateSnapshotForGroups(
        groups: Collection<Group>,
        result: UpdateResult
    ): Mono<UpdateResult> {
        val sample = Timer.start()
        versions.retainGroups(cache.groups())
        val results = Flux.fromIterable(groups)
            .doOnNextScheduledOn(groupSnapshotScheduler) { group ->
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
        return results.then(Mono.fromCallable {
            sample.stop(updateSnapshotForGroupsTimer)
            result
        })
    }

    private fun Flux<MultiClusterState>.createClusterConfigurations(): Flux<StatesAndClusters> = this
        .scan(StatesAndClusters.initial) { previous, currentStates -> StatesAndClusters(
            states = currentStates,
            clusters = snapshotFactory.clusterConfigurations(currentStates, previous.clusters)
        ) }
        .filter { it !== StatesAndClusters.initial }

    private data class StatesAndClusters(
        val states: MultiClusterState,
        val clusters: Map<String, ClusterConfiguration>
    ) {
        companion object {
            val initial = StatesAndClusters(MultiClusterState.empty(), emptyMap())
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
