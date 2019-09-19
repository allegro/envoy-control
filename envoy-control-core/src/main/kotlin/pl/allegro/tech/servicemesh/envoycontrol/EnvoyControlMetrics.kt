package pl.allegro.tech.servicemesh.envoycontrol

import java.util.concurrent.atomic.AtomicInteger

interface EnvoyControlMetrics {
    fun serviceRemoved()
    fun serviceAdded()
    fun instanceChanged()
    fun snapshotChanged()
    fun setCacheGroupsCount(count: Int)
    fun errorWatchingServices()
}

data class DefaultEnvoyControlMetrics(
    val servicesRemoved: AtomicInteger = AtomicInteger(),
    val servicesAdded: AtomicInteger = AtomicInteger(),
    val instanceChanges: AtomicInteger = AtomicInteger(),
    val snapshotChanges: AtomicInteger = AtomicInteger(),
    val cacheGroupsCount: AtomicInteger = AtomicInteger(),
    val errorWatchingServices: AtomicInteger = AtomicInteger()
) : EnvoyControlMetrics {

    override fun errorWatchingServices() {
        errorWatchingServices.incrementAndGet()
    }

    override fun serviceRemoved() {
        servicesRemoved.incrementAndGet()
    }

    override fun serviceAdded() {
        servicesAdded.incrementAndGet()
    }

    override fun instanceChanged() {
        instanceChanges.incrementAndGet()
    }

    override fun snapshotChanged() {
        snapshotChanges.incrementAndGet()
    }

    override fun setCacheGroupsCount(count: Int) {
        cacheGroupsCount.set(count)
    }
}
