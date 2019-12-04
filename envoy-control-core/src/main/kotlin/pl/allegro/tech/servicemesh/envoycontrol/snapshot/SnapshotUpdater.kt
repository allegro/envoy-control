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
import java.util.function.BiFunction

class SnapshotUpdater(
    private val cache: SnapshotCache<Group>,
    private val properties: SnapshotProperties,
    private val scheduler: Scheduler,
    private val onGroupAdded: Flux<List<Group>>,
    private val meterRegistry: MeterRegistry,
    serviceTagFilter: ServiceTagFilter = ServiceTagFilter(properties.routing.serviceTags)
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
        listenersFactory = EnvoyListenersFactory(serviceTagFilter),
        snapshotsVersions = versions,
        properties = properties,
        meterRegistry = meterRegistry,
        serviceTagFilter = serviceTagFilter
    )

    fun start(changes: Flux<List<LocalityAwareServicesState>>): Flux<GResult> {
        return Flux.merge(
            groups(),
            services(changes)
        ).doOnNext { result ->
            if (result.action == Action.ALL_GROUPS) {
                cache.groups().forEach { group ->
                    updateSnapshotForGroup(group, if (group.ads) lastAdsSnapshot else lastXdsSnapshot)
                }
            } else if (result.action == Action.SELECTED_GROUPS) {
                result.groups.forEach { group ->
                    updateSnapshotForGroup(group, if (group.ads) lastAdsSnapshot else lastXdsSnapshot)
                }
            }
        }
    }

    fun groups(): Flux<GResult> {
        // see GroupChangeWatcher
        return onGroupAdded
                .publishOn(scheduler)
                .map { groups ->
                    GResult(action = Action.SELECTED_GROUPS, groups=groups)
                }
                .onErrorResume { e ->
                    meterRegistry.counter("snapshot-updater.groups.updates.errors").increment()
                    logger.error("Unable to process new group", e)
                    Mono.justOrEmpty(GResult(action = Action.ERROR))
                }
    }

    fun services(changes: Flux<List<LocalityAwareServicesState>>): Flux<GResult> {
        return changes
            .sample(properties.stateSampleDuration)
            .publishOn(scheduler)
            .map { states ->
                updateSnapshots(states)
                GResult(action = Action.ALL_GROUPS)
            }
            .onErrorResume { e ->
                meterRegistry.counter("snapshot-updater.services.updates.errors").increment()
                logger.error("Unable to process service changes", e)
                Mono.justOrEmpty(GResult(action = Action.ALL_GROUPS))
            }
    }

    private fun updateSnapshots(states: List<LocalityAwareServicesState>) {
        lastXdsSnapshot = snapshotFactory.newSnapshot(states, ads = false)
        lastAdsSnapshot = snapshotFactory.newSnapshot(states, ads = true)
    }

    private fun updateSnapshotForGroup(group: Group, globalSnapshot: Snapshot) {
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

class GResult(val action: Any, val groups: List<Group> = listOf())
