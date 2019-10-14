package pl.allegro.tech.servicemesh.envoycontrol.config.redirect

import pl.allegro.tech.servicemesh.envoycontrol.config.BaseEnvoyTest
import pl.allegro.tech.servicemesh.envoycontrol.testcontainers.GenericContainer
import java.util.UUID

class RedirectServiceContainer(
    private val redirectTo: String
) : GenericContainer<RedirectServiceContainer>("schmunk42/nginx-redirect:latest") {

    val response = UUID.randomUUID().toString()

    override fun configure() {
        super.configure()
        withExposedPorts(PORT)
        withNetwork(BaseEnvoyTest.network)
        withEnv(mapOf(
            "SERVER_REDIRECT" to redirectTo,
            "SERVER_REDIRECT_PATH" to "/",
            "SERVER_REDIRECT_CODE" to "302"
        ))
    }

    fun address(): String = "${ipAddress()}:$PORT"

    companion object {
        const val PORT = 80
    }
}
