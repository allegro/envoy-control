package pl.allegro.tech.servicemesh.envoycontrol.config.service

import org.junit.jupiter.api.extension.ExtensionContext
import pl.allegro.tech.servicemesh.envoycontrol.config.sharing.BeforeAndAfterAllOnce
import pl.allegro.tech.servicemesh.envoycontrol.logger

open class GenericServiceExtension<T : ServiceContainer>(private val container: T) : ServiceExtension<T> {

    private val logger by logger()

    override fun container() = container

    override fun beforeAllOnce(context: ExtensionContext) {
        logger.info("Generic service is starting.")
        container.start()
        logger.info("Generic service extension started.")
    }

    override fun afterAllOnce(context: ExtensionContext) {
        container.stop()
    }

    override val ctx: BeforeAndAfterAllOnce.Context = BeforeAndAfterAllOnce.Context()

}
