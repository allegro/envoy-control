package pl.allegro.tech.servicemesh.envoycontrol.groups

import io.envoyproxy.controlplane.cache.ConfigWatcher
import io.envoyproxy.controlplane.cache.DeltaResponse
import io.envoyproxy.controlplane.cache.DeltaWatch
import io.envoyproxy.controlplane.cache.DeltaXdsRequest
import io.envoyproxy.controlplane.cache.Resources
import io.envoyproxy.controlplane.cache.Response
import io.envoyproxy.controlplane.cache.Watch
import io.envoyproxy.controlplane.cache.XdsRequest
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import pl.allegro.tech.servicemesh.envoycontrol.EnvoyControlMetrics
import pl.allegro.tech.servicemesh.envoycontrol.logger
import pl.allegro.tech.servicemesh.envoycontrol.utils.CHANGE_WATCHER_METRIC
import pl.allegro.tech.servicemesh.envoycontrol.utils.measureBuffer
import pl.allegro.tech.servicemesh.envoycontrol.utils.WATCH_TYPE_TAG
import reactor.core.publisher.Flux
import reactor.core.publisher.FluxSink
import java.util.function.Consumer
import pl.allegro.tech.servicemesh.envoycontrol.v3.SimpleCache as SimpleCache

/**
 * This class is needed to force snapshot creation in SnapshotUpdater when new group is added.
 * Otherwise when Envoy with new group is connected it won't receive the snapshot immediately.
 * In this situation, when there are no changes from ClusterStateChanges we won't send anything to Envoy.
 * When Envoy doesn't receive any snapshot from Envoy Control, it is stuck in PRE_INITIALIZING state.
 */
internal class GroupChangeWatcher(
    private val cache: SimpleCache<Group>,
    private val metrics: EnvoyControlMetrics,
    private val meterRegistry: MeterRegistry
) : ConfigWatcher {
    private val groupsChanged: Flux<List<Group>> = Flux.create { groupChangeEmitter = it }
    private var groupChangeEmitter: FluxSink<List<Group>>? = null
    //             []float64{.01, .1, 1, 3, 5, 10, 20, 30},
    private val pushTimeEds = Timer.builder("envoy_control_create_delta_watch")
    .serviceLevelObjectives(
    java.time.Duration.ofMillis(10),
    java.time.Duration.ofMillis(100),
    java.time.Duration.ofSeconds(1),
    java.time.Duration.ofSeconds(3),
    java.time.Duration.ofSeconds(5),
    java.time.Duration.ofSeconds(10),
    java.time.Duration.ofSeconds(20),
    java.time.Duration.ofSeconds(30)
    )
    .tags("type", "eds")
    .register(meterRegistry)

private val pushTimeRds = io.micrometer.core.instrument.Timer.builder("envoy_control_create_delta_watch")
    .serviceLevelObjectives(
    java.time.Duration.ofMillis(10),
    java.time.Duration.ofMillis(100),
    java.time.Duration.ofSeconds(1),
    java.time.Duration.ofSeconds(3),
    java.time.Duration.ofSeconds(5),
    java.time.Duration.ofSeconds(10),
    java.time.Duration.ofSeconds(20),
    java.time.Duration.ofSeconds(30)
    )
    .tags("type", "rds")
    .register(meterRegistry)

    private val pushTimeCds = io.micrometer.core.instrument.Timer.builder("envoy_control_create_delta_watch")
    .serviceLevelObjectives(
    java.time.Duration.ofMillis(10),
    java.time.Duration.ofMillis(100),
    java.time.Duration.ofSeconds(1),
    java.time.Duration.ofSeconds(3),
    java.time.Duration.ofSeconds(5),
    java.time.Duration.ofSeconds(10),
    java.time.Duration.ofSeconds(20),
    java.time.Duration.ofSeconds(30)
    )
    .tags("type", "cds")
    .register(meterRegistry)

    private val pushTimeLds = io.micrometer.core.instrument.Timer.builder("envoy_control_create_delta_watch")
    .serviceLevelObjectives(
    java.time.Duration.ofMillis(10),
    java.time.Duration.ofMillis(100),
    java.time.Duration.ofSeconds(1),
    java.time.Duration.ofSeconds(3),
    java.time.Duration.ofSeconds(5),
    java.time.Duration.ofSeconds(10),
    java.time.Duration.ofSeconds(20),
    java.time.Duration.ofSeconds(30)
    )
    .tags("type", "lds")
    .register(meterRegistry)
    private val pushTimeUnknown = io.micrometer.core.instrument.Timer.builder("envoy_control_create_delta_watch")
    .serviceLevelObjectives(
    java.time.Duration.ofMillis(10),
    java.time.Duration.ofMillis(100),
    java.time.Duration.ofSeconds(1),
    java.time.Duration.ofSeconds(3),
    java.time.Duration.ofSeconds(5),
    java.time.Duration.ofSeconds(10),
    java.time.Duration.ofSeconds(20),
    java.time.Duration.ofSeconds(30)
    )
    .tags("type", "unknown")
    .register(meterRegistry)
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
    ): DeltaWatch =
        getTimer(request!!).record<DeltaWatch> { val oldGroups = cache.groups()
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
            watch
        }!!


    private fun emitNewGroupsEvent(difference: List<Group>) {
        groupChangeEmitter?.next(difference)
    }

    fun getTimer(request: DeltaXdsRequest): Timer = when (request.resourceType) {
        Resources.ResourceType.ENDPOINT -> pushTimeEds
        Resources.ResourceType.ROUTE -> pushTimeRds
        Resources.ResourceType.CLUSTER -> pushTimeCds
        Resources.ResourceType.LISTENER -> pushTimeLds
        else -> pushTimeUnknown
    }

}
