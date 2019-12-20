package pl.allegro.tech.servicemesh.envoycontrol.synchronization

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.web.client.AsyncRestTemplate
import pl.allegro.tech.servicemesh.envoycontrol.services.ServicesState
import reactor.core.publisher.Mono
import java.net.URI
import java.util.concurrent.TimeUnit

class AsyncRestTemplateControlPlaneClient(
    private val asyncRestTemplate: AsyncRestTemplate,
    private val meterRegistry: MeterRegistry
) : AsyncControlPlaneClient {
    override fun getState(uri: URI): Mono<ServicesState> {
        return Mono.fromCompletionStage {
            asyncRestTemplate.getForEntity("$uri/state", ServicesState::class.java)
                .completable()
                .thenApply { it.body }
        }
            .elapsed()
            .map { t ->
                meterRegistry.timer("sync-dc.get-state.time").record(t.t1, TimeUnit.MILLISECONDS)
                t.t2
            }
    }
}
