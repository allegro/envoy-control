package pl.allegro.tech.servicemesh.envoycontrol.config.service

import org.junit.jupiter.api.extension.ExtensionContext
import pl.allegro.tech.servicemesh.envoycontrol.logger

class GenericServiceExtension<T : ServiceContainer>(private val container: T) : ServiceExtension<T> {

    private val logger by logger()
    private var started = false

    override fun container() = container

    override fun beforeAll(context: ExtensionContext) {
        if (started) {
            return
        }
        logger.info("Generic service is starting.")
        container.start()
        started = true
        logger.info("Generic service extension started.")
    }

    override fun afterAll(context: ExtensionContext) {
        container.stop()
    }
}
