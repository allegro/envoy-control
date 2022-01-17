package pl.allegro.tech.servicemesh.envoycontrol.config.service

interface HttpContainer: ServiceContainer {
    fun httpPort(): Int
}
