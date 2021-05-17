package pl.allegro.tech.servicemesh.envoycontrol.config.containers

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.testcontainers.containers.Network
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted

class ToxiproxyExtension(exposedPortsCount: Int = 0) : BeforeAllCallback, AfterAllCallback {
    private var started = false
    val container = ToxiproxyContainer(exposedPortsCount = exposedPortsCount).withNetwork(Network.SHARED)

    override fun beforeAll(context: ExtensionContext) {
        if (started) {
            return
        }

        container.start()
        awaitReady()
        started = true
    }

    private fun awaitReady() {
        untilAsserted {

            assertThat(container.containerInfo).isNotNull()
        }
    }

    override fun afterAll(context: ExtensionContext) {
    }
}
