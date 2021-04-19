package pl.allegro.tech.servicemesh.envoycontrol.config.service

import com.pszymczyk.consul.infrastructure.Ports
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import pl.allegro.tech.servicemesh.envoycontrol.config.BaseEnvoyTest
import pl.allegro.tech.servicemesh.envoycontrol.config.testcontainers.GenericContainer

class OAuthServerContainer :
    GenericContainer<OAuthServerContainer>("docker.pkg.github.com/kornelos/oauth-mock/oauth-mock:latest"),
    ServiceContainer {

    override fun configure() {
        super.configure()
        withNetwork(Network.SHARED)
        addFixedExposedPort(PORT,8080)
       waitingFor(Wait.forHttp("/").forStatusCode(200))
    }

    fun address(): String = "${ipAddress()}:$PORT"

    override fun port(): Int = PORT

    companion object {
         const val PORT = 50000
    }
}
