package pl.allegro.tech.servicemesh.envoycontrol.synchronization

import org.springframework.web.client.AsyncRestTemplate
import pl.allegro.tech.servicemesh.envoycontrol.services.ServicesState
import reactor.core.publisher.Mono
import java.net.URI

class AsyncRestTemplateControlPlaneClient(val asyncRestTemplate: AsyncRestTemplate) : AsyncControlPlaneClient {
    override fun getState(uri: URI): Mono<ServicesState> =
        asyncRestTemplate.getForEntity<ServicesState>("$uri/state", ServicesState::class.java)
            .completable()
            .thenApply { it.body }
            .let { Mono.fromCompletionStage(it) }
}
