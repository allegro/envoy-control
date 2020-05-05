package pl.allegro.tech.servicemesh.envoycontrol.services

import reactor.core.publisher.Flux
import java.util.concurrent.atomic.AtomicReference

// TODO(dj): #110 rename to ClusterStateChanges
interface ServiceChanges {
    fun stream(): Flux<MultiClusterState>
}

// TODO(dj): #110 rename to LocalClusterStateChanges
interface LocalServiceChanges : ServiceChanges {
    val latestServiceState: AtomicReference<ServicesState>
    fun isServiceStateLoaded(): Boolean
}
