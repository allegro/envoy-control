package pl.allegro.tech.servicemesh.envoycontrol.utils

import java.net.ServerSocket
import pl.allegro.tech.servicemesh.envoycontrol.logger

object Ports {
    private val usedPorts: MutableSet<Int> = mutableSetOf()
    val logger by logger()

    @Synchronized
    fun nextAvailable(): Int {
        var randomPort: Int
        do {
            randomPort = ServerSocket(0).use { it.localPort }
        } while (usedPorts.contains(randomPort))
        usedPorts.add(randomPort)
        logger.info("Generated port: {}", randomPort)
        logger.info("Used ports: {}", usedPorts)

        return randomPort
    }
}
