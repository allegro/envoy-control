package pl.allegro.tech.servicemesh.envoycontrol.server.callbacks

import io.envoyproxy.controlplane.cache.Resources
import io.envoyproxy.controlplane.server.DiscoveryServerCallbacks
import io.envoyproxy.envoy.api.v2.DiscoveryRequest
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest as v3DiscoveryRequest
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

    override fun onV3StreamRequest(streamId: Long, request: v3DiscoveryRequest?) {
    }

    override fun onV2StreamRequest(p0: Long, p1: DiscoveryRequest?) {
    }

    override fun onStreamCloseWithError(streamId: Long, typeUrl: String?, error: Throwable?) {
        connections.decrementAndGet()
        connectionsByType(typeUrl).decrementAndGet()
    }

    fun connections(type: MetricsStreamType): AtomicInteger = connectionsByType[type]!!

    @Suppress("ComplexMethod")
    private fun connectionsByType(typeUrl: String?): AtomicInteger {
        val type = when (typeUrl) {
            Resources.V2.CLUSTER_TYPE_URL -> MetricsStreamType.CDS
            Resources.V2.ENDPOINT_TYPE_URL -> MetricsStreamType.EDS
            Resources.V2.LISTENER_TYPE_URL -> MetricsStreamType.LDS
            Resources.V2.ROUTE_TYPE_URL -> MetricsStreamType.RDS
            Resources.V2.SECRET_TYPE_URL -> MetricsStreamType.SDS
            Resources.V3.CLUSTER_TYPE_URL -> MetricsStreamType.CDS
            Resources.V3.ENDPOINT_TYPE_URL -> MetricsStreamType.EDS
            Resources.V3.LISTENER_TYPE_URL -> MetricsStreamType.LDS
            Resources.V3.ROUTE_TYPE_URL -> MetricsStreamType.RDS
            Resources.V3.SECRET_TYPE_URL -> MetricsStreamType.SDS
            "" -> MetricsStreamType.ADS // ads is when the type url is empty
            else -> MetricsStreamType.UNKNOWN
        }
        return connectionsByType[type]!!
    }
}
