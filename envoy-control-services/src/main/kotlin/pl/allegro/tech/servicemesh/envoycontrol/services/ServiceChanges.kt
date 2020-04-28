package pl.allegro.tech.servicemesh.envoycontrol.services

import reactor.core.publisher.Flux

interface ServiceChanges {
    fun stream(): Flux<List<LocalityAwareServicesState>>
}
