package pl.allegro.tech.servicemesh.envoycontrol.config.service

import org.junit.jupiter.api.extension.ExtensionContext
import pl.allegro.tech.servicemesh.envoycontrol.config.sharing.ContainerPool

class EchoServiceExtension : ServiceExtension<EchoContainer> {

    companion object {
        private val pool = ContainerPool<EchoServiceExtension, EchoContainer> { EchoContainer() }
    }

    var started = false
    private var container: EchoContainer? = null

    override fun container() = container!!

    override fun beforeAll(context: ExtensionContext) {
        if (started) {
            return
        }

        container = pool.acquire(this)
        started = true
    }

    override fun afterAll(context: ExtensionContext) {
        pool.release(this)
    }
}
