package pl.allegro.tech.servicemesh.envoycontrol.config.service

import org.junit.jupiter.api.extension.ExtensionContext
import pl.allegro.tech.servicemesh.envoycontrol.config.sharing.ContainerPool

class OAuthServerExtension : ServiceExtension<OAuthServerContainer> {

    init {
        beforeAll(null)
    }

    var started = false
    private var container: OAuthServerContainer? = null

    override fun container(): OAuthServerContainer = container!!

    override fun beforeAll(context: ExtensionContext?) {
        if (started) {
            return
        } else {
            container = pool.acquire(this)
            started = true
        }
    }

    override fun afterAll(context: ExtensionContext?) {
        pool.release(this)
    }

    fun getTokenAddress(provider: String = "auth") = "http://localhost:${container().port()}/$provider/token"
    fun getJwksAddress(provider: String = "auth") = "http://${container().networkAlias()}:${container().oAuthPort()}/$provider/jwks"

    companion object {
        private val pool = ContainerPool<OAuthServerExtension, OAuthServerContainer> { OAuthServerContainer() }
    }
}
