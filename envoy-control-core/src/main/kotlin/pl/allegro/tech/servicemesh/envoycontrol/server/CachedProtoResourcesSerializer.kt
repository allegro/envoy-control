package pl.allegro.tech.servicemesh.envoycontrol.server

import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import com.google.protobuf.Any
import com.google.protobuf.Message
import io.envoyproxy.controlplane.cache.Resources
import io.envoyproxy.controlplane.cache.Resources.ApiVersion.V2
import io.envoyproxy.controlplane.cache.Resources.ApiVersion.V3
import io.envoyproxy.controlplane.server.serializer.DefaultProtoResourcesSerializer
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.binder.cache.GuavaCacheMetrics
import pl.allegro.tech.servicemesh.envoycontrol.utils.noopTimer
import java.util.function.Supplier

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

    private val cacheV2: Cache<Collection<Message>, MutableCollection<Any>> = createCache("protobuf-cache-v2")
    private val cacheV3: Cache<Collection<Message>, MutableCollection<Any>> = createCache("protobuf-cache-v3")
    private val v2Timer = createTimer(reportMetrics, meterRegistry, "protobuf-cache-v2.serialize.time")
    private val v3Timer = createTimer(reportMetrics, meterRegistry, "protobuf-cache-v3.serialize.time")

    private val deltaCacheV2: Cache<Message, Any> = createCache("protobuf-delta-cache-v2")
    private val deltaCacheV3: Cache<Message, Any> = createCache("protobuf-delta-cache-v3")
    private val deltaV2Timer = createTimer(reportMetrics, meterRegistry, "protobuf-delta-cache-v2.serialize.time")
    private val deltaV3Timer = createTimer(reportMetrics, meterRegistry, "protobuf-delta-cache-v3.serialize.time")

    private fun <K, V> createCache(cacheName: String): Cache<K, V> {
        return if (reportMetrics) {
            GuavaCacheMetrics
                    .monitor(
                            meterRegistry,
                            CacheBuilder.newBuilder()
                                    .recordStats()
                                    .weakValues()
                                    .build<K, V>(),
                            cacheName
                    )
        } else {
            CacheBuilder.newBuilder()
                    .weakValues()
                    .build<K, V>()
        }
    }

    override fun serialize(
        resources: MutableCollection<out Message>,
        apiVersion: Resources.ApiVersion
    ): MutableCollection<Any> {
        val timer = when (apiVersion) {
            V2 -> v2Timer
            V3 -> v3Timer
        }

        return timer.record(Supplier { getResources(resources, apiVersion) })
    }

    private fun getResources(
        resources: MutableCollection<out Message>,
        apiVersion: Resources.ApiVersion
    ): MutableCollection<Any> {
        val cache = when (apiVersion) {
            V2 -> cacheV2
            V3 -> cacheV3
        }

        return cache.get(resources) {
            resources.asSequence()
                .map { super.maybeRewriteTypeUrl(Any.pack(it), apiVersion) }
                .toMutableList()
        }
    }

    // override fun serialize(resource: Message, apiVersion: Resources.ApiVersion): Any {
    //     val cache = when (apiVersion) {
    //         V2 -> deltaCacheV2
    //         V3 -> deltaCacheV3
    //     }
    //
    //     val timer = when (apiVersion) {
    //         V2 -> deltaV2Timer
    //         V3 -> deltaV3Timer
    //     }
    //
    //     return timer.record(Supplier {
    //         cache.get(resource) {
    //             super.maybeRewriteTypeUrl(Any.pack(resource), apiVersion)
    //         }
    //     })
    // }
}
