package pl.allegro.tech.servicemesh.envoycontrol.synchronization

import io.micrometer.core.instrument.MeterRegistry
import pl.allegro.tech.servicemesh.envoycontrol.services.LocalityAwareServicesState
import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceChanges
import pl.allegro.tech.servicemesh.envoycontrol.utils.logSuppressedError
import pl.allegro.tech.servicemesh.envoycontrol.utils.measureBuffer
import pl.allegro.tech.servicemesh.envoycontrol.utils.onBackpressureLatestMeasured
import reactor.core.publisher.Flux
import reactor.core.scheduler.Schedulers

class GlobalServiceChanges(
    private val serviceChanges: Array<ServiceChanges>,
    private val meterRegistry: MeterRegistry,
    private val properties: SyncProperties
) {
    private val scheduler = Schedulers.newElastic("global-service-changes-combinator")

    fun combined(): Flux<List<LocalityAwareServicesState>> {
        // TODO(dj): #110 List<Flux<List<X>>> needs to look like domain oriented code
        val serviceStatesStreams: List<Flux<List<LocalityAwareServicesState>>> = serviceChanges.map { it.stream() }

        if (properties.combineServiceChangesExperimentalFlow) {
            return combinedExperimentalFlow(serviceStatesStreams)
        }

        return Flux.combineLatest(
            serviceStatesStreams.map {
                // if a number of items emitted by one source is very high, combineLatest may entirely ignore items
                // emitted by other sources. publishOn with multithreaded scheduler prevents it.
                it.publishOn(scheduler, 1)
            },
            1 // only prefetch one item to avoid processing stale consul states in case of backpressure
        ) { statesArray ->
            (statesArray.asSequence() as Sequence<List<LocalityAwareServicesState>>)
                .flatten()
                .toList()
        }
            .logSuppressedError("combineLatest() suppressed exception")
            .measureBuffer("global-service-changes-combine-latest", meterRegistry)
            .checkpoint("global-service-changes-emitted")
            .name("global-service-changes-emitted").metrics()
    }

    private fun combinedExperimentalFlow(
        serviceStatesStreams: List<Flux<List<LocalityAwareServicesState>>>
    ): Flux<List<LocalityAwareServicesState>> {

        return Flux.combineLatest(
            serviceStatesStreams.map {
                // if a number of items emitted by one source is very high, combineLatest may entirely ignore items
                // emitted by other sources. publishOn with multithreaded scheduler prevents it.
                //
                // onBackpressureLatest ensures that combineLatest sources respect backpressure, so combineLatest
                // will not fail with "Queue is full: Reactive Streams source doesn't respect backpressure" suppressed
                // error
                it.publishOn(scheduler, 1).onBackpressureLatest()
            }
        ) { statesArray ->
            (statesArray.asSequence() as Sequence<List<LocalityAwareServicesState>>)
                .flatten()
                .toList()
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
