package pl.allegro.tech.servicemesh.envoycontrol.services

import reactor.core.publisher.Flux
import java.util.concurrent.atomic.AtomicReference

interface ClusterStateChanges {
    fun stream(): Flux<MultiClusterState>
}

interface LocalClusterStateChanges : ClusterStateChanges {
    val latestServiceState: AtomicReference<ServicesState>
    fun isInitialStateLoaded(): Boolean
}
