package pl.allegro.tech.servicemesh.envoycontrol.config.envoy

import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.testcontainers.containers.Network
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.RandomConfigFile
import pl.allegro.tech.servicemesh.envoycontrol.config.containers.EchoServiceExtension
import pl.allegro.tech.servicemesh.envoycontrol.logger

class EnvoyExtension(envoyControl: EnvoyControlExtension,
                     val localService: EchoServiceExtension? = null)
    : BeforeAllCallback, AfterAllCallback {

    companion object {
        val logger by logger()
    }

    val container: EnvoyContainer = EnvoyContainer(
            RandomConfigFile,
            { localService?.container?.ipAddress() ?: "127.0.0.1" },
            envoyControl.app.grpcPort
    ).withNetwork(Network.SHARED)

    val ingressOperations: IngressOperations = IngressOperations(container)

    override fun beforeAll(context: ExtensionContext?) {
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

    override fun afterAll(context: ExtensionContext?) {
        container.stop()
    }

}