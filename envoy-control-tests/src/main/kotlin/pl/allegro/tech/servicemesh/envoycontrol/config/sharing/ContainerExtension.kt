package pl.allegro.tech.servicemesh.envoycontrol.config.sharing

import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.testcontainers.lifecycle.Startables
import pl.allegro.tech.servicemesh.envoycontrol.config.testcontainers.GenericContainer
import pl.allegro.tech.servicemesh.envoycontrol.logger

abstract class ContainerExtension : BeforeAndAfterAllOnce {
    companion object {
        val logger by logger()
    }

    abstract val container: GenericContainer<*>
    protected open fun preconditions(context: ExtensionContext) {}
    protected open fun waitUntilHealthy() {}

    override fun beforeAllOnce(context: ExtensionContext) {
        preconditions(context)
        logAndThrowError {
            container.start()
            waitUntilHealthy()
        }
    }

    private fun logAndThrowError(action: ContainerExtension.() -> Unit) = try {
        action(this)
    } catch (e: Exception) {
        logger.error("Logs from failed container: ${container.logs}", e)
        throw e
    }

    class Parallel(private vararg val extensions: ContainerExtension) : BeforeAndAfterAllOnce {
        override fun beforeAllOnce(context: ExtensionContext) {
            extensions.forEach { extension ->
                extension.logAndThrowError { preconditions(context) }
            }
            try {
                Startables.deepStart(extensions.map { it.container }).join()
            } catch (e: Exception) {
                logger.error("Starting containers in parallel failed", e)
            }
            extensions.forEach { extension ->
                extension.logAndThrowError { waitUntilHealthy() }
            }
        }

        override fun afterAllOnce(context: ExtensionContext) {
            extensions.forEach { extension -> extension.afterAll(context) }
        }

        override val ctx: BeforeAndAfterAllOnce.Context = BeforeAndAfterAllOnce.Context()
    }
}
