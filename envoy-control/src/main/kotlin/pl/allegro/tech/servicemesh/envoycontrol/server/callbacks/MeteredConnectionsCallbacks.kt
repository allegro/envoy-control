package pl.allegro.tech.servicemesh.envoycontrol.server.callbacks

import io.envoyproxy.controlplane.cache.Resources
import io.envoyproxy.controlplane.server.DiscoveryServerCallbacks
import java.util.concurrent.atomic.AtomicInteger

class MeteredConnectionsCallbacks(
    val connections: AtomicInteger = AtomicInteger()
) : DiscoveryServerCallbacks {

    private val connectionsByType: Map<MetricsStreamType, AtomicInteger>

    enum class MetricsStreamType {
        CDS, EDS, LDS, RDS, SDS, ADS, UNKNOWN
    }

    init {
        connectionsByType = MetricsStreamType.values()
            .map { type -> type to AtomicInteger(0) }
            .toMap()
    }

    override fun onStreamOpen(streamId: Long, typeUrl: String?) {
        connections.incrementAndGet()
        connectionsByType(typeUrl).incrementAndGet()
    }

    override fun onStreamClose(streamId: Long, typeUrl: String?) {
        connections.decrementAndGet()
        connectionsByType(typeUrl).decrementAndGet()
    }

    override fun onStreamCloseWithError(streamId: Long, typeUrl: String?, error: Throwable?) {
        connections.decrementAndGet()
        connectionsByType(typeUrl).decrementAndGet()
    }

    fun connections(type: MetricsStreamType): AtomicInteger = connectionsByType[type]!!

    private fun connectionsByType(typeUrl: String?): AtomicInteger {
        val type = when (typeUrl) {
            Resources.CLUSTER_TYPE_URL -> MetricsStreamType.CDS
            Resources.ENDPOINT_TYPE_URL -> MetricsStreamType.EDS
            Resources.LISTENER_TYPE_URL -> MetricsStreamType.LDS
            Resources.ROUTE_TYPE_URL -> MetricsStreamType.RDS
            Resources.SECRET_TYPE_URL -> MetricsStreamType.SDS
            "" -> MetricsStreamType.ADS // ads is when the type url is empty
            else -> MetricsStreamType.UNKNOWN
        }
        return connectionsByType[type]!!
    }
}
