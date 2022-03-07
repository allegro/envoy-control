package pl.allegro.tech.servicemesh.envoycontrol.snapshot

interface SnapshotChangeAuditor {
    fun audit(updateResult: UpdateResult)
}
