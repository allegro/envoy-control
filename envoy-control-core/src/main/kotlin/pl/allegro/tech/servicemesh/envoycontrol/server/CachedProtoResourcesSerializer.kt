package pl.allegro.tech.servicemesh.envoycontrol.server

import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import com.google.protobuf.Any
import com.google.protobuf.Message
import io.envoyproxy.controlplane.server.serializer.ProtoResourcesSerializer
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.cache.GuavaCacheMetrics
import java.util.function.Supplier

internal class CachedProtoResourcesSerializer(
    private val meterRegistry: MeterRegistry,
    private val reportMetrics: Boolean
) : ProtoResourcesSerializer {

    private val cache: Cache<Collection<Message>, MutableCollection<Any>> = if (reportMetrics) {
        GuavaCacheMetrics
            .monitor(
                meterRegistry,
                CacheBuilder.newBuilder()
                    .recordStats()
                    .weakValues()
                    .build<Collection<Message>, MutableCollection<Any>>(),
                "protobuf-cache"
            )
    } else {
        CacheBuilder.newBuilder()
            .weakValues()
            .build<Collection<Message>, MutableCollection<Any>>()
    }

    override fun serialize(resources: MutableCollection<out Message>): MutableCollection<Any> {
        return if (reportMetrics) {
            meterRegistry.timer("protobuf-cache.serialize.time")
                .record(Supplier { getResources(resources) })
        } else {
            getResources(resources)
        }
    }

    private fun getResources(resources: MutableCollection<out Message>): MutableCollection<Any> {
        return cache.get(resources) {
            resources.asSequence()
                .map { Any.pack(it) }
                .toMutableList()
        }
    }

    @Suppress("NotImplementedDeclaration")
    override fun serialize(resource: Message?): Any {
        throw NotImplementedError("Serializing single messages is not supported")
    }
}
