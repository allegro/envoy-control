package pl.allegro.tech.servicemesh.envoycontrol.snapshot

import io.envoyproxy.controlplane.cache.Snapshot
import io.envoyproxy.controlplane.cache.SnapshotCache
import io.micrometer.core.instrument.MeterRegistry
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group
import pl.allegro.tech.servicemesh.envoycontrol.logger
import pl.allegro.tech.servicemesh.envoycontrol.services.LocalityAwareServicesState
import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceName
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler
import java.util.function.BiFunction

class SnapshotUpdater(
    private val cache: SnapshotCache<Group>,
    private val properties: SnapshotProperties,
    private val scheduler: Scheduler,
    private val onGroupAdded: Flux<out Any>,
    private val meterRegistry: MeterRegistry
) {
    companion object {
        private val logger by logger()
    }

    private val versions = SnapshotsVersions()
    private val snapshotFactory = EnvoySnapshotFactory(
        ingressRoutesFactory = EnvoyIngressRoutesFactory(properties),
        egressRoutesFactory = EnvoyEgressRoutesFactory(properties),
        clustersFactory = EnvoyClustersFactory(properties),
        snapshotsVersions = versions,
        properties = properties
    )

    fun start(changes: Flux<List<LocalityAwareServicesState>>): Flux<List<List<LocalityAwareServicesState>>> {
        // see GroupChangeWatcher
        return Flux.combineLatest(
            onGroupAdded,
            changes,
            BiFunction<Any, List<LocalityAwareServicesState>, List<LocalityAwareServicesState>> { _, states -> states }
        )
            .sample(properties.stateSampleDuration)
            .startWith(emptyList<LocalityAwareServicesState>())
            .buffer(2, 1)
            .publishOn(scheduler)
            .doOnNext { states ->
                val oldState = states[0]
                val newState = states[1]
                versions.retainGroups(cache.groups())
                updateSnapshots(oldState, newState)
            }
            .onErrorResume { e ->
                meterRegistry.counter("snapshot-updater.services.updates.errors").increment()
                logger.error("Unable to process service changes", e)
                Mono.justOrEmpty(listOf())
            }
    }

    private fun updateSnapshots(
        oldState: List<LocalityAwareServicesState>,
        newState: List<LocalityAwareServicesState>
    ) {
        cache.groups().forEach { group ->
            // low cost path
            if (group.isGlobalGroup() || (cache.getSnapshot(group) == null) || hasWildcardDependency(group)) {
                chooseSnapshot(group, newState)
            } else {
                // high cost path
                val changedServiceNames = getServiceNamesOfInstancesThatChanged(newState, oldState)
                val serviceDependenciesNames = group.proxySettings.outgoing.dependencies.map { it.getName() }

                if (dependencyChanged(changedServiceNames, serviceDependenciesNames)) {
                    chooseSnapshot(group, newState)
                }
            }
        }
    }

    private fun hasWildcardDependency(group: Group): Boolean {
        return group.proxySettings.outgoing.dependencies.map { it.getName() }.contains("*")
    }

    private fun dependencyChanged(
        changedServiceNames: List<ServiceName>,
        serviceDependenciesNames: List<String>
    ): Boolean {
        return (changedServiceNames.intersect(serviceDependenciesNames)).isNotEmpty()
    }

    private fun getServiceNamesOfInstancesThatChanged(
        newState: List<LocalityAwareServicesState>,
        oldState: List<LocalityAwareServicesState>
    ): List<ServiceName> {
        return (newState - oldState).flatMap {
            it.servicesState.serviceNames()
        }
    }

    private fun chooseSnapshot(group: Group, newState: List<LocalityAwareServicesState>) {
        updateSnapshotForGroup(group, if (group.ads) {
            snapshotFactory.newSnapshot(newState, ads = true)
        } else {
            snapshotFactory.newSnapshot(newState, ads = false)
        })
    }

    private fun updateSnapshotForGroup(group: Group, snapshot: Snapshot) {
        try {
            val groupSnapshot = snapshotFactory.getSnapshotForGroup(group, snapshot)
            cache.setSnapshot(group, groupSnapshot)
        } catch (e: Throwable) {
            meterRegistry.counter("snapshot-updater.services.${group.serviceName}.updates.errors").increment()
            logger.error("Unable to create snapshot for group ${group.serviceName}", e)
        }
    }
}
