package pl.allegro.tech.servicemesh.envoycontrol.config.containers

import org.testcontainers.containers.wait.strategy.Wait
import pl.allegro.tech.servicemesh.envoycontrol.config.BaseEnvoyTest
import pl.allegro.tech.servicemesh.envoycontrol.testcontainers.GenericContainer
import java.util.UUID

class HttpBinContainer : GenericContainer<HttpBinContainer>("kennethreitz/httpbin:latest") {

    val response = UUID.randomUUID().toString()

    override fun configure() {
        super.configure()
        withExposedPorts(PORT)
        withNetwork(BaseEnvoyTest.network)
        waitingFor(Wait.forHttp("/").forStatusCode(200))
    }

    fun address(): String = "${ipAddress()}:$PORT"

    companion object {
        const val PORT = 80
    }
}
