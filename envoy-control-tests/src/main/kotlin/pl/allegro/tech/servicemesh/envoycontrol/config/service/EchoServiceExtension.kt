package pl.allegro.tech.servicemesh.envoycontrol.config.service

import org.junit.jupiter.api.extension.ExtensionContext
import pl.allegro.tech.servicemesh.envoycontrol.config.sharing.BeforeAndAfterAllOnce
import pl.allegro.tech.servicemesh.envoycontrol.config.sharing.ContainerPool

class EchoServiceExtension : ServiceExtension<EchoContainer> {

    companion object {
        private val pool = ContainerPool<EchoServiceExtension, EchoContainer> { EchoContainer() }
    }

    private var container: EchoContainer? = null

    override fun container() = container!!

    override fun beforeAllOnce(context: ExtensionContext) {
        container = pool.acquire(this)
    }

    override fun afterAllOnce(context: ExtensionContext) {
        pool.release(this)
    }

    override val ctx: BeforeAndAfterAllOnce.Context = BeforeAndAfterAllOnce.Context()
}
