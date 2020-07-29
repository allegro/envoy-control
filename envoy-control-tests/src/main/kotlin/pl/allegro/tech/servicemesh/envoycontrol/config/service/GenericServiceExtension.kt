package pl.allegro.tech.servicemesh.envoycontrol.config.service

import org.junit.jupiter.api.extension.ExtensionContext

class GenericServiceExtension<T : ServiceContainer>(private val container: T) : ServiceExtension<T> {

    private var started = false

    override fun container() = container

    override fun beforeAll(context: ExtensionContext) {
        if (started) {
            return
        }

        container.start()
        started = true
    }

    override fun afterAll(context: ExtensionContext) {
        container.stop()
    }
}
