package pl.allegro.tech.servicemesh.envoycontrol.config.service

import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.ImageFromDockerfile
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.ResponseWithBody
import pl.allegro.tech.servicemesh.envoycontrol.config.testcontainers.GenericContainer

class OAuthServerContainer :
    GenericContainer<OAuthServerContainer>(ImageFromDockerfile().withFileFromClasspath("Dockerfile", "oauth/Dockerfile")),
    ServiceContainer {

    override fun configure() {
        super.configure()
        withEnv("PORT", OAUTH_PORT.toString())
        withNetwork(Network.SHARED)
        withNetworkAliases(NETWORK_ALIAS)
        addExposedPort(OAUTH_PORT)
        waitingFor(Wait.forHttp("/").forStatusCode(200))
    }

    fun address(): String = "http://${ipAddress()}:${getMappedPort(OAUTH_PORT)}"

    override fun port(): Int = getMappedPort(OAUTH_PORT)

    fun oAuthPort() = OAUTH_PORT

    fun networkAlias() = NETWORK_ALIAS

    companion object {
        const val NETWORK_ALIAS = "oauth"
        const val OAUTH_PORT = 9997
    }
}
