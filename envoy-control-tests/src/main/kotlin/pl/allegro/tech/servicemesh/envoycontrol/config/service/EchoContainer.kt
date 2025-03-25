package pl.allegro.tech.servicemesh.envoycontrol.config.service

import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.ResponseWithBody
import pl.allegro.tech.servicemesh.envoycontrol.config.testcontainers.GenericContainer
import java.util.Locale
import java.util.UUID

class EchoContainer(val response: String = UUID.randomUUID().toString()) :
    GenericContainer<EchoContainer>("jxlwqq/http-echo"), ServiceContainer, UpstreamService {

    override fun configure() {
        super.configure()
        withExposedPorts(PORT)
        withNetwork(Network.SHARED)
        withCommand(String.format(Locale.getDefault(), "--text=%s --addr=:%s", response, PORT))
        waitingFor(Wait.forHttp("/").forStatusCode(200))
    }

    fun address(): String = "${ipAddress()}:$PORT"

    override fun port() = PORT
    override fun isSourceOf(response: ResponseWithBody) = response.body.contains(this.response)
    override fun id(): String = containerId

    companion object {
        const val PORT = 5678
    }
}
