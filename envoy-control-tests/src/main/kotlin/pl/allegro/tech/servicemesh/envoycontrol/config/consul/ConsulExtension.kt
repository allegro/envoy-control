package pl.allegro.tech.servicemesh.envoycontrol.config.consul

import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.testcontainers.containers.Network
import pl.allegro.tech.servicemesh.envoycontrol.logger

class ConsulExtension : BeforeAllCallback, AfterAllCallback, AfterEachCallback {

    companion object {
        private val SHARED_CONSUL = ConsulSetup(
            Network.SHARED,
            ConsulServerConfig(1, "dc1", expectNodes = 1)
        )
    }

    val server = SHARED_CONSUL
    private val logger by logger()
    private var started = false

    override fun beforeAll(context: ExtensionContext) {
        if (started) {
            return
        }
        logger.info("Consul extension is starting.")
        server.container.start()
        started = true
        logger.info("Consul extension started.")
    }

    override fun afterEach(context: ExtensionContext) {
        server.operations.deregisterAll()
    }

    override fun afterAll(context: ExtensionContext) {
    }
}
