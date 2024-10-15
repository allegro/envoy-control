package pl.allegro.tech.servicemesh.envoycontrol

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import java.util.concurrent.atomic.AtomicInteger

interface EnvoyControlMetrics {
    fun serviceRemoved()
    fun serviceAdded()
    fun instanceChanged()
    fun snapshotChanged()
    fun setCacheGroupsCount(count: Int)
    fun errorWatchingServices()
    val meterRegistry: MeterRegistry
}

data class DefaultEnvoyControlMetrics(
    val servicesRemoved: AtomicInteger = AtomicInteger(),
    val servicesAdded: AtomicInteger = AtomicInteger(),
    val instanceChanges: AtomicInteger = AtomicInteger(),
    val snapshotChanges: AtomicInteger = AtomicInteger(),
    val cacheGroupsCount: AtomicInteger = AtomicInteger(),
    val errorWatchingServices: AtomicInteger = AtomicInteger(),
    override val meterRegistry: MeterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
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
