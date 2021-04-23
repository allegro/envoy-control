package pl.allegro.tech.servicemesh.envoycontrol.config.consul

import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.testcontainers.containers.Network


class ConsulMultiClusterExtension : BeforeAllCallback, AfterAllCallback, AfterEachCallback {

    companion object {
        private val FIRST_CLUSTER = ConsulClusterSetup(listOf(
            ConsulSetup(Network.SHARED, ConsulServerConfig(1, "dc1")),
            ConsulSetup(Network.SHARED, ConsulServerConfig(2, "dc1")),
            ConsulSetup(Network.SHARED, ConsulServerConfig(3, "dc1")))
        )
        private val SECOND_CLUSTER = ConsulClusterSetup(listOf(
            ConsulSetup(Network.SHARED, ConsulServerConfig(1, "dc2")),
            ConsulSetup(Network.SHARED, ConsulServerConfig(2, "dc2")),
            ConsulSetup(Network.SHARED, ConsulServerConfig(3, "dc2")))
        )
    }

    val serverFirst =  FIRST_CLUSTER
    val serverSecond =  SECOND_CLUSTER
    private var started = false

    override fun beforeAll(context: ExtensionContext?) {
        if(started) {
            return
        }
        serverFirst.start()
        serverSecond.start()
        serverFirst.joinWith(serverSecond)

        started = true
    }

    override fun afterAll(context: ExtensionContext?) {
    }

    override fun afterEach(context: ExtensionContext?) {
        FIRST_CLUSTER.operations.deregisterAll()
        SECOND_CLUSTER.operations.deregisterAll()
    }
}
