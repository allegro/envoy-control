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

    fun start(changes: Flux<List<LocalityAwareServicesState>>): Flux<List<LocalityAwareServicesState>> {
        return Flux.combineLatest(
            groups(),
            services(changes),
            BiFunction<Any, List<LocalityAwareServicesState>, List<LocalityAwareServicesState>> { _, states -> states }
        )
    }

    fun groups(): Flux<List<Group>> {
        // see GroupChangeWatcher
        return onGroupAdded
                .publishOn(scheduler)
                .doOnNext { groups ->
                    groups.forEach { group ->
                        if (group.ads) {
                            if (::lastAdsSnapshot.isInitialized) {
                                updateSnapshotForGroup(group, lastAdsSnapshot)
                            } else {
                                logger.error("Somehow an envoy connected before generating the first snapshot," +
                                        "this indicates a problem with initial state loading HC")
                            }
                        } else {
                            if (::lastXdsSnapshot.isInitialized) {
                                updateSnapshotForGroup(group, lastXdsSnapshot)
                            } else {
                                logger.error("Somehow an envoy connected before generating the first snapshot," +
                                        "this indicates a problem with initial state loading HC")
                            }
                        }
                    }
                }
                .onErrorResume { e ->
                    meterRegistry.counter("snapshot-updater.groups.updates.errors").increment()
                    logger.error("Unable to process new group", e)
                    Mono.justOrEmpty(listOf())
                }
    }

    fun services(changes: Flux<List<LocalityAwareServicesState>>): Flux<List<LocalityAwareServicesState>> {
        return changes
            .sample(properties.stateSampleDuration)
            .publishOn(scheduler)
            .doOnNext { states ->
                versions.retainGroups(cache.groups())
                updateSnapshots(states)
            }
            .onErrorResume { e ->
                meterRegistry.counter("snapshot-updater.services.updates.errors").increment()
                logger.error("Unable to process service changes", e)
                Mono.justOrEmpty(listOf())
            }
    }

    private fun updateSnapshots(states: List<LocalityAwareServicesState>) {
        lastXdsSnapshot = snapshotFactory.newSnapshot(states, ads = false)
        lastAdsSnapshot = snapshotFactory.newSnapshot(states, ads = true)

        cache.groups().forEach { group ->
            updateSnapshotForGroup(group, if (group.ads) lastAdsSnapshot else lastXdsSnapshot)
        }
    }

    private fun updateSnapshotForGroup(group: Group, globalSnapshot: Snapshot) {
        try {
            val groupSnapshot = snapshotFactory.getSnapshotForGroup(group, globalSnapshot)
            cache.setSnapshot(group, groupSnapshot)
        } catch (e: Throwable) {
            meterRegistry.counter("snapshot-updater.services.${group.serviceName}.updates.errors").increment()
            logger.error("Unable to create globalSnapshot for group ${group.serviceName}", e)
        }
    }
}
