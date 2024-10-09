package pl.allegro.tech.servicemesh.envoycontrol.synchronization

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import org.springframework.web.client.RestTemplate
import pl.allegro.tech.servicemesh.envoycontrol.services.ServicesState
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
            metered {
                restTemplate.getForEntity("$uri/state", ServicesState::class.java).body!!
            }
        }, executors)
    }

    private fun <T> metered(function: () -> T): T {
        try {
            val response = timed { function() }
            success()
            return response
        } catch (e: Exception) {
            failure()
            throw e
        }
    }

    private fun <T> timed(function: () -> T): T {
        return meterRegistry.timer("cross.dc.synchronization.seconds", Tags.of("operation", "get-state"))
            .record(function)
    }

    private fun success() {
        meterRegistry.counter("cross.dc.synchronization", Tags.of("operation", "get-state", "status", "success"))
            .increment()
    }

    private fun failure() {
        meterRegistry.counter("cross.dc.synchronization", Tags.of("operation", "get-state", "status", "failure"))
            .increment()
    }
}
