package pl.allegro.tech.servicemesh.envoycontrol.synchronization

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.web.client.RestTemplate
import pl.allegro.tech.servicemesh.envoycontrol.services.ServicesState
import pl.allegro.tech.servicemesh.envoycontrol.utils.GzipUtils
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

class RestTemplateControlPlaneClient(
    private val restTemplate: RestTemplate,
    private val meterRegistry: MeterRegistry,
    private val executors: Executor
) : ControlPlaneClient {
    override fun getState(uri: URI): CompletableFuture<ServicesState> {
        return CompletableFuture.supplyAsync({
            metered(meterRegistry) {
                restTemplate.getForEntity("$uri/state", ServicesState::class.java).body!!
            }
        }, executors)
    }
}

class GzipRestTemplateControlPlaneClient(
    private val restTemplate: RestTemplate,
    private val meterRegistry: MeterRegistry,
    private val gzipUtils: GzipUtils,
    private val executors: Executor
) : ControlPlaneClient {
    override fun getState(uri: URI): CompletableFuture<ServicesState> {
        return CompletableFuture.supplyAsync({
            metered(meterRegistry) {
                restTemplate.getForEntity("$uri/state", ByteArray::class.java).body!!
                    .let { gzipUtils.unGzip(it, ServicesState::class.java) }
            }
        }, executors)
    }
}

private fun <T> metered(meterRegistry: MeterRegistry, function: () -> T): T {
    try {
        val response = meterRegistry.timed { function() }
        meterRegistry.success()
        return response
    } catch (e: Exception) {
        meterRegistry.failure()
        throw e
    }
}

private fun <T> MeterRegistry.timed(function: () -> T): T {
    return this.timer("sync-dc.get-state.time").record(function)
}

private fun MeterRegistry.success() {
    this.counter("sync-dc.get-state.success").increment()
}

private fun MeterRegistry.failure() {
    this.counter("sync-dc.get-state.failure").increment()
}
