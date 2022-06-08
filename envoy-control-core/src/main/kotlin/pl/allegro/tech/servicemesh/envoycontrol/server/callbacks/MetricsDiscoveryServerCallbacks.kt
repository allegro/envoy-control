package pl.allegro.tech.servicemesh.envoycontrol.server.callbacks

import io.envoyproxy.controlplane.cache.Resources
import io.envoyproxy.controlplane.server.DiscoveryServerCallbacks
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest as V3DiscoveryRequest
import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.atomic.AtomicInteger

class MetricsDiscoveryServerCallbacks(private val meterRegistry: MeterRegistry) : DiscoveryServerCallbacks {

    private val connections: AtomicInteger = AtomicInteger()
    private val connectionsByType: Map<StreamType, AtomicInteger>

    enum class StreamType {
        CDS, EDS, LDS, RDS, SDS, ADS, UNKNOWN;

        companion object {
            fun fromTypeUrl(typeUrl: String) = when (typeUrl) {
                // TODO_deprecate_v2: do we need this mapping still?
                Resources.V2.CLUSTER_TYPE_URL -> CDS
                Resources.V2.ENDPOINT_TYPE_URL -> EDS
                Resources.V2.LISTENER_TYPE_URL -> LDS
                Resources.V2.ROUTE_TYPE_URL -> RDS
                Resources.V2.SECRET_TYPE_URL -> SDS
                Resources.V3.CLUSTER_TYPE_URL -> CDS
                Resources.V3.ENDPOINT_TYPE_URL -> EDS
                Resources.V3.LISTENER_TYPE_URL -> LDS
                Resources.V3.ROUTE_TYPE_URL -> RDS
                Resources.V3.SECRET_TYPE_URL -> SDS
                "" -> ADS // ads is when the type url is empty
                else -> UNKNOWN
            }
        }
    }

    init {
        connectionsByType = StreamType.values()
            .map { type -> type to AtomicInteger(0) }
            .toMap()

        meterRegistry.gauge("grpc.all-connections", connections)
        connectionsByType.forEach { (type, typeConnections) ->
            meterRegistry.gauge("grpc.connections.${type.name.toLowerCase()}", typeConnections)
        }
    }

    override fun onStreamOpen(streamId: Long, typeUrl: String) {
        connections.incrementAndGet()
        connectionsByType(typeUrl).incrementAndGet()
    }

    override fun onStreamClose(streamId: Long, typeUrl: String) {
        connections.decrementAndGet()
        connectionsByType(typeUrl).decrementAndGet()
    }

    override fun onV3StreamRequest(streamId: Long, request: V3DiscoveryRequest) {
        meterRegistry.counter("grpc.requests.${StreamType.fromTypeUrl(request.typeUrl).name.toLowerCase()}")
            .increment()
    }

    override fun onStreamCloseWithError(streamId: Long, typeUrl: String, error: Throwable) {
        connections.decrementAndGet()
        connectionsByType(typeUrl).decrementAndGet()
    }

    private fun connectionsByType(typeUrl: String) = connectionsByType[StreamType.fromTypeUrl(typeUrl)]!!
}
