package pl.allegro.tech.servicemesh.envoycontrol.config.envoy

import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.testcontainers.containers.Network
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.RandomConfigFile
import pl.allegro.tech.servicemesh.envoycontrol.config.echo.ServiceExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.logger

class EnvoyExtension(
    envoyControl: EnvoyControlExtension,
    private val localService: ServiceExtension<*>? = null,
    config: EnvoyConfig = RandomConfigFile
) : BeforeAllCallback, AfterAllCallback {

    companion object {
        val logger by logger()
    }

    val container: EnvoyContainer = EnvoyContainer(
        config,
        { localService?.container?.ipAddress() ?: "127.0.0.1" },
        envoyControl.app.grpcPort
    ).withNetwork(Network.SHARED)

    val ingressOperations: IngressOperations = IngressOperations(container)
    val egressOperations: EgressOperations = EgressOperations(container)

    override fun beforeAll(context: ExtensionContext) {
        if (localService != null && !localService.started) {
            localService.beforeAll(context)
        }

        try {
            container.start()
        } catch (e: Exception) {
            logger.error("Logs from failed container: ${container.logs}")
            throw e
        }
    }

    override fun afterAll(context: ExtensionContext) {
        container.stop()
    }
}
