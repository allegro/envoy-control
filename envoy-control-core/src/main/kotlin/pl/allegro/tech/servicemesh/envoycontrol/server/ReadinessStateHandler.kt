package pl.allegro.tech.servicemesh.envoycontrol.server

interface ReadinessStateHandler {
    fun ready()
    fun unready()
}

object NoopReadinessStateHandler: ReadinessStateHandler {
    override fun ready() {
        return
    }

    override fun unready() {
        return
    }
}
