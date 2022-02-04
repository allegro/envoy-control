package pl.allegro.tech.servicemesh.envoycontrol.config.service

import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import pl.allegro.tech.servicemesh.envoycontrol.config.testcontainers.GenericContainer

class RedisContainer: GenericContainer<EchoContainer>("redis:alpine"), ServiceContainer {

    override fun configure() {
        super.configure()
        withExposedPorts(PORT)
        withNetwork(Network.SHARED)
        waitingFor(Wait.forListeningPort())
    }

    fun address(): String = "${ipAddress()}:$PORT"

    override fun port() = PORT

    companion object {
        const val PORT = 6379
    }
}
