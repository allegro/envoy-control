package pl.allegro.tech.servicemesh.envoycontrol.server

import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import com.google.protobuf.Any
import com.google.protobuf.Message
import io.envoyproxy.controlplane.cache.Resources
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.cache.GuavaCacheMetrics
import pl.allegro.tech.servicemesh.envoycontrol.utils.noopTimer
import java.util.function.Supplier

import io.envoyproxy.controlplane.server.serializer.DefaultProtoResourcesSerializer
import io.micrometer.core.instrument.Timer

internal class CachedProtoResourcesSerializer(
    private val meterRegistry: MeterRegistry,
    private val reportMetrics: Boolean
) : DefaultProtoResourcesSerializer() {

    private fun createTimer(reportMetrics: Boolean, meterRegistry: MeterRegistry, timerName: String): Timer {
        return if (reportMetrics) {
            meterRegistry.timer(timerName)
        } else {
            noopTimer
        }
    }

    private val cache: Cache<Collection<Message>, MutableCollection<Any>> = createCache("protobuf-cache")
    private val timer = createTimer(reportMetrics, meterRegistry, "protobuf-cache.serialize.time")

    private fun createCache(cacheName: String): Cache<Collection<Message>, MutableCollection<Any>> {
        return if (reportMetrics) {
            GuavaCacheMetrics
                    .monitor(
                            meterRegistry,
                            CacheBuilder.newBuilder()
                                    .recordStats()
                                    .weakValues()
                                    .build(),
                            cacheName
                    )
        } else {
            CacheBuilder.newBuilder()
                    .weakValues()
                    .build()
        }
    }

    override fun serialize(
        resources: MutableCollection<out Message>,
        apiVersion: Resources.ApiVersion
    ): MutableCollection<Any> {
        return timer.record(Supplier { getResources(resources) })
    }

    private fun getResources(resources: MutableCollection<out Message>): MutableCollection<Any> {
        return cache.get(resources) {
            resources.asSequence()
                .map { Any.pack(it) }
                .toMutableList()
        }
    }

    @Suppress("NotImplementedDeclaration")
    override fun serialize(resource: Message?, apiVersion: Resources.ApiVersion?): Any {
        throw NotImplementedError("Serializing single messages is not supported")
    }
}
