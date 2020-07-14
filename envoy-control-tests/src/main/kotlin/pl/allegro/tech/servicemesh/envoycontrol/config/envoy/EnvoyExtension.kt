package pl.allegro.tech.servicemesh.envoycontrol.config.envoy

import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.testcontainers.containers.Network
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.RandomConfigFile
import pl.allegro.tech.servicemesh.envoycontrol.logger

class EnvoyExtension(envoyControl: EnvoyControlExtension) : BeforeAllCallback, AfterAllCallback {

    companion object {
        val logger by logger()
    }

    val container: EnvoyContainer = EnvoyContainer(
            RandomConfigFile, "127.0.0.1", envoyControl.app.grpcPort
    ).withNetwork(Network.SHARED)

    override fun beforeAll(context: ExtensionContext?) {
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