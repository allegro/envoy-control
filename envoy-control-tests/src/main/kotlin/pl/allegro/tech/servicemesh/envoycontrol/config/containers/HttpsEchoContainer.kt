package pl.allegro.tech.servicemesh.envoycontrol.config.containers

import org.testcontainers.containers.BindMode
import pl.allegro.tech.servicemesh.envoycontrol.config.BaseEnvoyTest

class HttpsEchoContainer : SSLGenericContainer<HttpsEchoContainer>("mendhak/http-https-echo") {

    private val FULLCHAIN_PATH = "testcontainers/ssl/fullchain.pem"
    private val FULLCHAIN_PATH_DEST = "/app/fullchain.pem"

    private val PRIVKEY_PATH = "testcontainers/ssl/privkey.pem"
    private val PRIVKEY_PATH_DEST = "/app/privkey.pem"

    override fun configure() {
        super.configure()
        withNetwork(BaseEnvoyTest.network)
        withClasspathResourceMapping(FULLCHAIN_PATH, FULLCHAIN_PATH_DEST, BindMode.READ_ONLY)
        withClasspathResourceMapping(PRIVKEY_PATH, PRIVKEY_PATH_DEST, BindMode.READ_ONLY)
    }
}
