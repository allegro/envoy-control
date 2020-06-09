package pl.allegro.tech.servicemesh.envoycontrol.synchronization

import io.micrometer.core.instrument.MeterRegistry
import pl.allegro.tech.servicemesh.envoycontrol.services.MultiZoneState
import pl.allegro.tech.servicemesh.envoycontrol.services.MultiZoneState.Companion.toMultiZoneState
import pl.allegro.tech.servicemesh.envoycontrol.services.ZoneStateChanges
import pl.allegro.tech.servicemesh.envoycontrol.utils.logSuppressedError
import pl.allegro.tech.servicemesh.envoycontrol.utils.measureBuffer
import pl.allegro.tech.servicemesh.envoycontrol.utils.onBackpressureLatestMeasured
import reactor.core.publisher.Flux
import reactor.core.scheduler.Schedulers

class GlobalStateChanges(
    private val zoneStateChanges: Array<ZoneStateChanges>,
    private val meterRegistry: MeterRegistry,
    private val properties: SyncProperties
) {
    private val scheduler = Schedulers.newElastic("global-service-changes-combinator")

    fun combined(): Flux<MultiZoneState> {
        val zoneStatesStreams: List<Flux<MultiZoneState>> = zoneStateChanges.map { it.stream() }

        if (properties.combineServiceChangesExperimentalFlow) {
            return combinedExperimentalFlow(zoneStatesStreams)
        }

        return Flux.combineLatest(
            zoneStatesStreams.map {
                // if a number of items emitted by one source is very high, combineLatest may entirely ignore items
                // emitted by other sources. publishOn with multithreaded scheduler prevents it.
                it.publishOn(scheduler, 1)
            },
            1 // only prefetch one item to avoid processing stale consul states in case of backpressure
        ) { statesArray ->
            @Suppress("UNCHECKED_CAST")
            (statesArray.asSequence() as Sequence<MultiZoneState>)
                .flatten()
                .toList()
                .toMultiZoneState()
        }
            .logSuppressedError("combineLatest() suppressed exception")
            .measureBuffer("global-service-changes-combine-latest", meterRegistry)
            .checkpoint("global-service-changes-emitted")
            .name("global-service-changes-emitted").metrics()
    }

    private fun combinedExperimentalFlow(
        zoneStatesStreams: List<Flux<MultiZoneState>>
    ): Flux<MultiZoneState> {

        return Flux.combineLatest(
            zoneStatesStreams.map {
                // if a number of items emitted by one source is very high, combineLatest may entirely ignore items
                // emitted by other sources. publishOn with multithreaded scheduler prevents it.
                //
                // onBackpressureLatest ensures that combineLatest sources respect backpressure, so combineLatest
                // will not fail with "Queue is full: Reactive Streams source doesn't respect backpressure" suppressed
                // error
                it.publishOn(scheduler, 1).onBackpressureLatest()
            }
        ) { statesArray ->
            @Suppress("UNCHECKED_CAST")
            (statesArray.asSequence() as Sequence<MultiZoneState>)
                .flatten()
                .toList()
                .toMultiZoneState()
        }
            .logSuppressedError("combineLatest() suppressed exception")
            .measureBuffer("global-service-changes-combine-latest", meterRegistry)
            .checkpoint("global-service-changes-emitted")
            .name("global-service-changes-emitted").metrics()
            .onBackpressureLatestMeasured("global-service-changes-backpressure", meterRegistry)
            .publishOn(scheduler, 1)
            .checkpoint("global-service-changes-published")
            .name("global-service-changes-published").metrics()
    }
}
