package pl.allegro.tech.servicemesh.envoycontrol.config.consul

import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.testcontainers.containers.Network
import pl.allegro.tech.servicemesh.envoycontrol.config.sharing.BeforeAndAfterAllOnce
import pl.allegro.tech.servicemesh.envoycontrol.logger

class ConsulExtension(private val deregisterAfterEach: Boolean = true) : BeforeAndAfterAllOnce, AfterEachCallback {

    companion object {
        private val SHARED_CONSUL = ConsulSetup(
            Network.SHARED,
            ConsulServerConfig(1, "dc1", expectNodes = 1)
        )
    }

    val server = SHARED_CONSUL
    private val logger by logger()

    override fun beforeAllOnce(context: ExtensionContext) {
        logger.info("Consul extension is starting.")
        server.container.start()
        logger.info("Consul extension started.")
    }

    override fun afterEach(context: ExtensionContext) {
        if (deregisterAfterEach) {
            server.operations.deregisterAll()
        }
    }

    override fun afterAllOnce(context: ExtensionContext) {
        server.operations.deregisterAll()
    }

    override val ctx: BeforeAndAfterAllOnce.Context = BeforeAndAfterAllOnce.Context()
}
