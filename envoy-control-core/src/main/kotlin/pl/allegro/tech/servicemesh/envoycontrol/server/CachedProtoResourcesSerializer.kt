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

    private val cacheV2: Cache<Message, Any> = createCache("protobuf-cache-v2")
    private val cacheV3: Cache<Message, Any> = createCache("protobuf-cache-v3")
    private val v2Timer = createTimer(reportMetrics, meterRegistry, "protobuf-cache-v2.serialize.time")
    private val v3Timer = createTimer(reportMetrics, meterRegistry, "protobuf-cache-v3.serialize.time")

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

    private fun createTimer(reportMetrics: Boolean, meterRegistry: MeterRegistry, timerName: String): Timer {
        return if (reportMetrics) {
            meterRegistry.timer(timerName)
        } else {
            noopTimer
        }
    }

    override fun serialize(resource: Message, apiVersion: Resources.ApiVersion): Any {
        val cache = when (apiVersion) {
            V2 -> cacheV2
            V3 -> cacheV3
        }

        val timer = when (apiVersion) {
            V2 -> v2Timer
            V3 -> v3Timer
        }

        return timer.record(Supplier {
            cache.get(resource) {
                super.maybeRewriteTypeUrl(
                    Any.pack(
                        resource
                    ), apiVersion
                )
            }
        })
    }
}
