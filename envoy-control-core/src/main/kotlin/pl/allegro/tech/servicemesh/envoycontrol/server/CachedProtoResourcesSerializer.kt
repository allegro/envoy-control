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
import pl.allegro.tech.servicemesh.envoycontrol.utils.PROTOBUF_CACHE_METRIC

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

    private val cache: Cache<Message, Any> = createCache("protobuf-cache")
    private val timer = createTimer(reportMetrics, meterRegistry, PROTOBUF_CACHE_METRIC)

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

    override fun serialize(resource: Message, apiVersion: Resources.ApiVersion): Any {
        return timer.record(Supplier {
            cache.get(resource) {
                    Any.pack(
                        resource
                )
            }
        })
    }
}
