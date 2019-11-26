package pl.allegro.tech.servicemesh.envoycontrol.groups

import com.google.common.collect.Sets
import io.envoyproxy.controlplane.cache.ConfigWatcher
import io.envoyproxy.controlplane.cache.Response
import io.envoyproxy.controlplane.cache.SimpleCache
import io.envoyproxy.controlplane.cache.Watch
import io.envoyproxy.envoy.api.v2.DiscoveryRequest
import pl.allegro.tech.servicemesh.envoycontrol.EnvoyControlMetrics
import reactor.core.publisher.Flux
import reactor.core.publisher.FluxSink
import java.util.function.Consumer

/**
 * This class is needed to force snapshot creation in SnapshotUpdater when new group is added.
 * Otherwise when Envoy with new group is connected it won't receive the snapshot immediately.
 * In this situation, when there are no changes from ServiceChanges we won't send anything to Envoy.
 * When Envoy doesn't receive any snapshot from Envoy Control, it is stuck in PRE_INITIALIZING state.
 */
internal class GroupChangeWatcher(
    private val cache: SimpleCache<Group>,
    private val metrics: EnvoyControlMetrics
) : ConfigWatcher {
    private val groupAddedFlux: Flux<List<Group>> = Flux.create { groupAddedSink = it }
    private var groupAddedSink: FluxSink<List<Group>>? = null

    fun onGroupAdded(): Flux<List<Group>> {
        return groupAddedFlux
    }

    override fun createWatch(
        ads: Boolean,
        request: DiscoveryRequest,
        knownResourceNames: MutableSet<String>,
        responseConsumer: Consumer<Response>
    ): Watch {
        val oldGroups = cache.groups()
        val watch = cache.createWatch(ads, request, knownResourceNames, responseConsumer)
        val groups = cache.groups()
        metrics.setCacheGroupsCount(groups.size)
        if (oldGroups != groups) {
            val symmetricDifference = Sets.symmetricDifference(oldGroups.toSet(), groups.toSet())
            emitNewGroupsEvent(symmetricDifference)
        }
        return watch
    }

    private fun emitNewGroupsEvent(symmetricDifference: Sets.SetView<Group>) {
        groupAddedSink?.next(symmetricDifference.toList())
    }
}
