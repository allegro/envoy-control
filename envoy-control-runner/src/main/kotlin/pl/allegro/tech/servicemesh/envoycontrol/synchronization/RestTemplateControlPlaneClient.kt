package pl.allegro.tech.servicemesh.envoycontrol.synchronization

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.web.client.RestTemplate
import pl.allegro.tech.servicemesh.envoycontrol.services.ServicesState
import java.net.URI

class RestTemplateControlPlaneClient(
    private val restTemplate: RestTemplate,
    private val meterRegistry: MeterRegistry
) : ControlPlaneClient {
    override fun getState(uri: URI): ServicesState {
        return metered {
            restTemplate.getForEntity("$uri/state", ServicesState::class.java).body!!
        }
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
        return meterRegistry.timer("sync-dc.get-state.time").record(function)
    }

    private fun success() {
        meterRegistry.counter("sync-dc.get-state.success").increment()
    }

    private fun failure() {
        meterRegistry.counter("sync-dc.get-state.failure").increment()
    }
}
