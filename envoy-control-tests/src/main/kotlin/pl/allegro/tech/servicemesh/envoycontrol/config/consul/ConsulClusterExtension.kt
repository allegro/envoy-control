package pl.allegro.tech.servicemesh.envoycontrol.config.consul

import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.testcontainers.containers.Network

class ConsulClusterExtension(val dc: String) : BeforeAllCallback, AfterAllCallback, AfterEachCallback {

    val consuls = listOf(
        ConsulSetup(Network.SHARED, ConsulServerConfig(1, dc)),
        ConsulSetup(Network.SHARED, ConsulServerConfig(2, dc)),
        ConsulSetup(Network.SHARED, ConsulServerConfig(3, dc))
    )

    val server = consuls.first()
    private var started = false

    override fun beforeAll(context: ExtensionContext?) {
        if (started) {
            return
        }
        consuls.forEach { consul ->
            consul.container.start()
        }
        consuls.forEach { consul ->
            val consulContainerNames = consuls.map { it.container.containerName() }.toTypedArray()
            val args = arrayOf("consul", "join", *consulContainerNames)
            consul.container.execInContainer(*args)
        }
        started = true
    }

    override fun afterAll(context: ExtensionContext?) {
    }

    override fun afterEach(context: ExtensionContext?) {
        server.operations.deregisterAll()
    }
}
