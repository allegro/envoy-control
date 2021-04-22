package pl.allegro.tech.servicemesh.envoycontrol.config.service

import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import pl.allegro.tech.servicemesh.envoycontrol.config.testcontainers.GenericContainer

class OAuthServerContainer :
    GenericContainer<OAuthServerContainer>("docker.pkg.github.com/kornelos/oauth-mock/oauth-mock:0.0.1"),
    ServiceContainer {

    override fun configure() {
        super.configure()
        withNetwork(Network.SHARED)
        withNetworkAliases("oauth")
        addExposedPort(8080)
        waitingFor(Wait.forHttp("/").forStatusCode(200))
    }

    fun address(): String = "http://${ipAddress()}:${getMappedPort(PORT)}"

    override fun port(): Int = getMappedPort(PORT)

    companion object {
        const val PORT = 8080
    }
}
