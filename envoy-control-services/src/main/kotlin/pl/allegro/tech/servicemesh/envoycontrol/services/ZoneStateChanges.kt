package pl.allegro.tech.servicemesh.envoycontrol.services

import reactor.core.publisher.Flux
import java.util.concurrent.atomic.AtomicReference

interface ZoneStateChanges {
    fun stream(): Flux<MultiZoneState>
}

interface LocalZoneStateChanges : ZoneStateChanges {
    val latestServiceState: AtomicReference<ServicesState>
    fun isInitialStateLoaded(): Boolean
}
