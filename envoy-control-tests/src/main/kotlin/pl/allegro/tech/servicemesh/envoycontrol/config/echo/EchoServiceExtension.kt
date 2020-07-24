package pl.allegro.tech.servicemesh.envoycontrol.config.echo

import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext

class EchoServiceExtension : BeforeAllCallback, AfterAllCallback {

    val container = EchoContainer()
    var started = false

    override fun beforeAll(context: ExtensionContext?) {
        if (started) {
            return
        }

        container.start()
        started = true
    }

    override fun afterAll(context: ExtensionContext?) {
        container.stop()
    }
}
