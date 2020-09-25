package pl.allegro.tech.servicemesh.envoycontrol.server

import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import com.google.protobuf.Any
import com.google.protobuf.Message
import io.envoyproxy.controlplane.cache.Resources
import io.envoyproxy.controlplane.server.serializer.DefaultProtoResourcesSerializer
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.cache.GuavaCacheMetrics
import pl.allegro.tech.servicemesh.envoycontrol.utils.noopTimer
import java.util.function.Supplier

internal class CachedProtoResourcesSerializer(
    meterRegistry: MeterRegistry,
    reportMetrics: Boolean
) : DefaultProtoResourcesSerializer() {

    private val serializeTimer = if (reportMetrics) {
        meterRegistry.timer("protobuf-cache.serialize.time")
    } else {
        noopTimer
    }

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

    override fun serialize(
            resources: MutableCollection<out Message>,
            apiVersion: Resources.ApiVersion
    ): MutableCollection<Any> = serializeTimer.record(Supplier { getResources(resources, apiVersion) })

    private fun getResources(
            resources: MutableCollection<out Message>,
            apiVersion: Resources.ApiVersion
    ): MutableCollection<Any> {
        return cache.get(resources) {
            resources.asSequence()
                .map { super.maybeRewriteTypeUrl(Any.pack(it), apiVersion) }
                .toMutableList()
        }
    }

    @Suppress("NotImplementedDeclaration")
    override fun serialize(resource: Message?, apiVersion: Resources.ApiVersion): Any {
        throw NotImplementedError("Serializing single messages is not supported")
    }
}
