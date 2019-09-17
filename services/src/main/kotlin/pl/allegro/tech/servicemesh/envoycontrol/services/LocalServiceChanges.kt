package pl.allegro.tech.servicemesh.envoycontrol.services

import java.util.concurrent.atomic.AtomicReference

interface LocalServiceChanges : ServiceChanges {
    val latestServiceState: AtomicReference<ServicesState>
    fun isServiceStateLoaded(): Boolean
}
