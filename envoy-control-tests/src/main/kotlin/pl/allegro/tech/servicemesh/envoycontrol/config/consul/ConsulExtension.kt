package pl.allegro.tech.servicemesh.envoycontrol.config.consul

import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.testcontainers.containers.Network

class ConsulExtension : BeforeAllCallback, AfterAllCallback, AfterEachCallback {

    val server: ConsulSetup = ConsulSetup(
            Network.SHARED,
            ConsulServerConfig(1, "dc1", expectNodes = 1)
    )
    var started = false

    override fun beforeAll(context: ExtensionContext) {
        if (started) {
            return
        }

        server.container.start()
        started = true
    }

    override fun afterEach(context: ExtensionContext) {
        server.operations.deregisterAll()
    }

    override fun afterAll(context: ExtensionContext) {
        server.container.stop()
    }
}
