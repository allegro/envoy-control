package pl.allegro.tech.servicemesh.envoycontrol.config.containers

import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext

class EchoServiceExtension : BeforeAllCallback, AfterAllCallback {

    val container = EchoContainer()

    override fun beforeAll(context: ExtensionContext?) {
        container.start()
    }

    override fun afterAll(context: ExtensionContext?) {
        container.stop()
    }

}