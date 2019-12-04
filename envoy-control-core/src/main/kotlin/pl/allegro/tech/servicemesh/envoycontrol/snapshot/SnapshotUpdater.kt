package pl.allegro.tech.servicemesh.envoycontrol.snapshot

import io.envoyproxy.controlplane.cache.Snapshot
import io.envoyproxy.controlplane.cache.SnapshotCache
import io.micrometer.core.instrument.MeterRegistry
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group
import pl.allegro.tech.servicemesh.envoycontrol.logger
import pl.allegro.tech.servicemesh.envoycontrol.services.LocalityAwareServicesState
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler

class SnapshotUpdater(
    private val cache: SnapshotCache<Group>,
    private val properties: SnapshotProperties,
    private val scheduler: Scheduler,
    private val onGroupAdded: Flux<List<Group>>,
    private val meterRegistry: MeterRegistry
) {
    companion object {
        private val logger by logger()
    }

    private lateinit var lastAdsSnapshot: Snapshot
    private lateinit var lastXdsSnapshot: Snapshot
    private val versions = SnapshotsVersions()
    private val snapshotFactory = EnvoySnapshotFactory(
        ingressRoutesFactory = EnvoyIngressRoutesFactory(properties),
        egressRoutesFactory = EnvoyEgressRoutesFactory(properties),
        clustersFactory = EnvoyClustersFactory(properties),
        snapshotsVersions = versions,
        properties = properties
    )

    fun start(changes: Flux<List<LocalityAwareServicesState>>): Flux<UpdateResult> {
        return Flux.merge(
                services(changes),
                groups()
        ).doOnNext { result ->
            val groups = if (result.action == Action.ALL_GROUPS) {
                cache.groups()
            } else {
                result.groups
            }

            groups.forEach { group ->
                if (group.ads) {
                    updateSnapshotForGroup(group, lastAdsSnapshot)
                } else {
                    updateSnapshotForGroup(group, lastXdsSnapshot)
                }
            }
        }
    }

    fun groups(): Flux<UpdateResult> {
        // see GroupChangeWatcher
        return onGroupAdded
                .publishOn(scheduler)
                .map { groups ->
                    UpdateResult(action = Action.SELECTED_GROUPS, groups = groups)
                }
                .onErrorResume { e ->
                    meterRegistry.counter("snapshot-updater.groups.updates.errors").increment()
                    logger.error("Unable to process new group", e)
                    Mono.justOrEmpty(UpdateResult(action = Action.ERROR))
                }
    }

    fun services(changes: Flux<List<LocalityAwareServicesState>>): Flux<UpdateResult> {
        return changes
                .sample(properties.stateSampleDuration)
                .publishOn(scheduler)
                .map { states ->
                    updateSnapshots(states)
                    UpdateResult(action = Action.ALL_GROUPS)
                }
                .onErrorResume { e ->
                    meterRegistry.counter("snapshot-updater.services.updates.errors").increment()
                    logger.error("Unable to process service changes", e)
                    Mono.justOrEmpty(UpdateResult(action = Action.ALL_GROUPS))
                }
    }

    private fun updateSnapshots(states: List<LocalityAwareServicesState>) {
        lastXdsSnapshot = snapshotFactory.newSnapshot(states, ads = false)
        lastAdsSnapshot = snapshotFactory.newSnapshot(states, ads = true)
    }

    fun updateSnapshotForGroup(group: Group, globalSnapshot: Snapshot) {
        try {
            val groupSnapshot = snapshotFactory.getSnapshotForGroup(group, globalSnapshot)
            versions.retainGroups(cache.groups())
            cache.setSnapshot(group, groupSnapshot)
        } catch (e: Throwable) {
            meterRegistry.counter("snapshot-updater.services.${group.serviceName}.updates.errors").increment()
            logger.error("Unable to create globalSnapshot for group ${group.serviceName}", e)
        }
    }
}

enum class Action {
    SELECTED_GROUPS, ALL_GROUPS, ERROR
}

class UpdateResult(val action: Any, val groups: List<Group> = listOf())
