package pl.allegro.tech.servicemesh.envoycontrol.snapshot

import reactor.core.publisher.Mono

interface SnapshotChangeAuditor {
    fun audit(previousUpdateResult: UpdateResult, actualUpdateResult: UpdateResult): Mono<Any>
}

object NoopSnapshotChangeAuditor: SnapshotChangeAuditor {
    override fun audit(previousUpdateResult: UpdateResult, actualUpdateResult: UpdateResult): Mono<Any> {
        return Mono.empty()
    }
}
