package pl.allegro.tech.servicemesh.envoycontrol.config.echo

import org.testcontainers.containers.wait.strategy.Wait
import pl.allegro.tech.servicemesh.envoycontrol.config.BaseEnvoyTest
import pl.allegro.tech.servicemesh.envoycontrol.testcontainers.GenericContainer
import java.util.UUID

class EchoContainer : GenericContainer<EchoContainer>("hashicorp/http-echo:latest") {

    val response = UUID.randomUUID().toString()

    override fun configure() {
        super.configure()
        withExposedPorts(PORT)
        withNetwork(BaseEnvoyTest.network)
        withCommand(String.format("-text=%s", response))
        waitingFor(Wait.forHttp("/").forStatusCode(200))
    }

    fun address(): String = "${ipAddress()}:$PORT"

    companion object {
        const val PORT = 5678
    }
}
