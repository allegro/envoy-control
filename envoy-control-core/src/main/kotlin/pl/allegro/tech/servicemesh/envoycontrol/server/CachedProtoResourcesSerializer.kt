package pl.allegro.tech.servicemesh.envoycontrol.server

import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import com.google.protobuf.Any
import com.google.protobuf.Message
import io.envoyproxy.controlplane.server.serializer.ProtoResourcesSerializer
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.binder.cache.GuavaCacheMetrics

internal class CachedProtoResourcesSerializer(
    val meterRegistry: MeterRegistry
) : ProtoResourcesSerializer {

    private val monitorCache: Cache<Collection<Message>, MutableCollection<Any>> = GuavaCacheMetrics
        .monitor(meterRegistry,
            CacheBuilder.newBuilder()
                .recordStats()
                .weakValues()
                .build<Collection<Message>, MutableCollection<Any>>(),
            "protoCache", "cacheName", "stat")

    override fun serialize(resources: MutableCollection<out Message>): MutableCollection<Any> {
        val startSerialize = Timer.start(meterRegistry)
        val result = monitorCache.get(resources) {
            resources.asSequence()
                .map { Any.pack(it) }
                .toMutableList()
        }
        startSerialize.stop(meterRegistry.timer("proto-cache.serialize.${resources.size}.time"))
        return result
    }

    @Suppress("NotImplementedDeclaration")
    override fun serialize(resource: Message?): Any {
        throw NotImplementedError("Serializing single messages is not supported")
    }
}
