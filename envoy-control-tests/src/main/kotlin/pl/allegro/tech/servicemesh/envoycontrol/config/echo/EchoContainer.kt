package pl.allegro.tech.servicemesh.envoycontrol.config.echo

import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import pl.allegro.tech.servicemesh.envoycontrol.config.testcontainers.GenericContainer
import java.util.UUID

class EchoContainer : GenericContainer<EchoContainer>("hashicorp/http-echo:latest"), ServiceContainer {

    val response = UUID.randomUUID().toString()

    override fun configure() {
        super.configure()
        withExposedPorts(PORT)
        withNetwork(Network.SHARED)
        withCommand(String.format("-text=%s", response))
        waitingFor(Wait.forHttp("/").forStatusCode(200))
    }

    fun address(): String = "${ipAddress()}:$PORT"

    override fun port() = PORT

    companion object {
        const val PORT = 5678
    }
}
