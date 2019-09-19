package pl.allegro.tech.servicemesh.envoycontrol.snapshot

import io.envoyproxy.controlplane.cache.Snapshot
import io.envoyproxy.controlplane.cache.SnapshotCache
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group
import pl.allegro.tech.servicemesh.envoycontrol.services.LocalityAwareServicesState
import reactor.core.publisher.Flux
import reactor.core.scheduler.Scheduler
import java.util.function.BiFunction

class SnapshotUpdater(
    private val cache: SnapshotCache<Group>,
    private val properties: SnapshotProperties,
    private val scheduler: Scheduler,
    private val onGroupAdded: Flux<out Any>
) {
    private val versions = SnapshotsVersions()
    private val snapshotFactory = EnvoySnapshotFactory(
        ingressRoutesFactory = EnvoyIngressRoutesFactory(properties),
        egressRoutesFactory = EnvoyEgressRoutesFactory(properties),
        clustersFactory = EnvoyClustersFactory(properties),
        snapshotsVersions = versions,
        properties = properties
    )

    fun start(changes: Flux<List<LocalityAwareServicesState>>): Flux<List<LocalityAwareServicesState>> {
        // see GroupChangeWatcher
        return Flux.combineLatest(
            onGroupAdded,
            changes,
            BiFunction<Any, List<LocalityAwareServicesState>, List<LocalityAwareServicesState>> { _, states -> states }
        )
            .sample(properties.stateSampleDuration)
            .publishOn(scheduler)
            .doOnNext { states ->
                versions.retainGroups(cache.groups())
                updateSnapshots(states)
            }
    }

    private fun updateSnapshots(states: List<LocalityAwareServicesState>) {
        val snapshot = snapshotFactory.newSnapshot(states, ads = false)
        val adsSnapshot = snapshotFactory.newSnapshot(states, ads = true)

        cache.groups().forEach { group -> updateSnapshotForGroup(group, if (group.ads) adsSnapshot else snapshot) }
    }

    private fun updateSnapshotForGroup(group: Group, snapshot: Snapshot) {
        val groupSnapshot = snapshotFactory.getSnapshotForGroup(group, snapshot)
        cache.setSnapshot(group, groupSnapshot)
    }
}
