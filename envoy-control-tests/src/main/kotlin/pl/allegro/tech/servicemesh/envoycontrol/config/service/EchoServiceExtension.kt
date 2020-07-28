package pl.allegro.tech.servicemesh.envoycontrol.config.service

class EchoServiceExtension(shared: Boolean = true) : ServiceExtension<EchoContainer>(
    if (shared) SHARED_CONTAINER else EchoContainer(),
    shared = shared
) {

    companion object {
        private val SHARED_CONTAINER = EchoContainer()
    }
}
