package pl.allegro.tech.servicemesh.envoycontrol.config.service

import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import pl.allegro.tech.servicemesh.envoycontrol.config.testcontainers.GenericContainer
import java.util.UUID
import java.util.Locale

class EchoContainer : GenericContainer<EchoContainer>("larstobi/http-echo:0.2.4"), ServiceContainer {

    val response = UUID.randomUUID().toString()

    override fun configure() {
        super.configure()
        withExposedPorts(PORT)
        withNetwork(Network.SHARED)
        withCommand(String.format(Locale.getDefault(), "-text=%s", response))
        waitingFor(Wait.forHttp("/").forStatusCode(200))
    }

    fun address(): String = "${ipAddress()}:$PORT"

    override fun port() = PORT

    companion object {
        const val PORT = 5678
    }
}
