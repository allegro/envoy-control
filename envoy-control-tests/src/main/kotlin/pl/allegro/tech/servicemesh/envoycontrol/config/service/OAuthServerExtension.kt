package pl.allegro.tech.servicemesh.envoycontrol.config.service

import org.junit.jupiter.api.extension.ExtensionContext
import pl.allegro.tech.servicemesh.envoycontrol.config.sharing.BeforeAndAfterAllOnce
import pl.allegro.tech.servicemesh.envoycontrol.config.sharing.ContainerPool

class OAuthServerExtension : ServiceExtension<OAuthServerContainer> {

    private var container: OAuthServerContainer? = null

    override fun container(): OAuthServerContainer = container!!

    override fun beforeAllOnce(context: ExtensionContext) {
        container = pool.acquire(this)
    }

    override fun afterAllOnce(context: ExtensionContext) {
        pool.release(this)
    }

    override val ctx: BeforeAndAfterAllOnce.Context = BeforeAndAfterAllOnce.Context()

    fun getTokenAddress(provider: String = "auth") = "http://localhost:${container().port()}/$provider/token"
    fun getJwksAddress(provider: String = "auth") =
        "http://${container().networkAlias()}:${container().oAuthPort()}/$provider/jwks"

    companion object {
        private val pool = ContainerPool<OAuthServerExtension, OAuthServerContainer> { OAuthServerContainer() }
    }
}
