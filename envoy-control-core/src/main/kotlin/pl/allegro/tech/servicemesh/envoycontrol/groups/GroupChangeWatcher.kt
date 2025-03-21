package pl.allegro.tech.servicemesh.envoycontrol.groups

import io.envoyproxy.controlplane.cache.ConfigWatcher
import io.envoyproxy.controlplane.cache.DeltaResponse
import io.envoyproxy.controlplane.cache.DeltaWatch
import io.envoyproxy.controlplane.cache.DeltaXdsRequest
import io.envoyproxy.controlplane.cache.Response
import io.envoyproxy.controlplane.cache.SnapshotCache
import io.envoyproxy.controlplane.cache.Watch
import io.envoyproxy.controlplane.cache.XdsRequest
import io.envoyproxy.controlplane.cache.v3.Snapshot
import io.micrometer.core.instrument.MeterRegistry
import java.util.function.Consumer
import pl.allegro.tech.servicemesh.envoycontrol.EnvoyControlMetrics
import pl.allegro.tech.servicemesh.envoycontrol.logger
import pl.allegro.tech.servicemesh.envoycontrol.utils.CHANGE_WATCHER_METRIC
import pl.allegro.tech.servicemesh.envoycontrol.utils.WATCH_TYPE_TAG
import pl.allegro.tech.servicemesh.envoycontrol.utils.measureBuffer
import reactor.core.publisher.Flux
import reactor.core.publisher.FluxSink

/**
 * This class is needed to force snapshot creation in SnapshotUpdater when new group is added.
 * Otherwise when Envoy with new group is connected it won't receive the snapshot immediately.
 * In this situation, when there are no changes from ClusterStateChanges we won't send anything to Envoy.
 * When Envoy doesn't receive any snapshot from Envoy Control, it is stuck in PRE_INITIALIZING state.
 */
internal class GroupChangeWatcher(
    private val cache: SnapshotCache<Group, Snapshot>,
    private val metrics: EnvoyControlMetrics,
    private val meterRegistry: MeterRegistry
) : ConfigWatcher {
    private val groupsChanged: Flux<List<Group>> = Flux.create { groupChangeEmitter = it }
    private var groupChangeEmitter: FluxSink<List<Group>>? = null

    private val logger by logger()

    fun onGroupAdded(): Flux<List<Group>> {
        return groupsChanged
            .measureBuffer("group-change-watcher", meterRegistry)
            .checkpoint("group-change-watcher-emitted")
            .name(CHANGE_WATCHER_METRIC)
            .tag(WATCH_TYPE_TAG, "group")
            .metrics()
            .doOnSubscribe {
                logger.info("Watching group changes")
            }
            .doOnCancel {
                logger.warn("Cancelling watching group changes")
            }
    }

    override fun createWatch(
        ads: Boolean,
        request: XdsRequest?,
        knownResourceNames: MutableSet<String>?,
        responseConsumer: Consumer<Response>?,
        hasClusterChanged: Boolean,
        allowDefaultEmptyEdsUpdate: Boolean
    ): Watch {
        val oldGroups = cache.groups()

        val watch = cache.createWatch(
            ads,
            request,
            knownResourceNames,
            responseConsumer,
            hasClusterChanged,
            allowDefaultEmptyEdsUpdate
        )
        val groups = cache.groups()
        metrics.setCacheGroupsCount(groups.size)
        if (oldGroups != groups) {
            emitNewGroupsEvent(groups - oldGroups)
        }
        return watch
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
        val oldGroups = cache.groups()

        val watch = cache.createDeltaWatch(
            request,
            requesterVersion,
            resourceVersions,
            pendingResources,
            isWildcard,
            responseConsumer,
            hasClusterChanged
        )
        val groups = cache.groups()
        metrics.setCacheGroupsCount(groups.size)
        if (oldGroups != groups) {
            emitNewGroupsEvent(groups - oldGroups)
        }
        return watch
    }

    private fun emitNewGroupsEvent(difference: List<Group>) {
        groupChangeEmitter?.next(difference)
    }
}
