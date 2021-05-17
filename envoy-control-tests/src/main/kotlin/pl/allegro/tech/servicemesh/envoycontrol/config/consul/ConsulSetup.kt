package pl.allegro.tech.servicemesh.envoycontrol.config.consul

import com.pszymczyk.consul.infrastructure.Ports
import org.testcontainers.containers.Network
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers
class ConsulSetup(
    network: Network,
    consulConfig: ConsulConfig,
    val port: Int = Ports.nextAvailable()
) {
    val container: ConsulContainer = ConsulContainer(consulConfig.dc, port, consulConfig.id, consulConfig)
        .withNetwork(network)
    val operations = ConsulOperations(port)
}

class ConsulClusterSetup(val consulSetups: List<ConsulSetup>) {
    val container = consulSetups.first()
    val operations = consulSetups.first().operations
    val port = consulSetups.first().port

    fun start() {
        consulSetups.forEach { consul ->
            consul.container.start()
        }
        consulSetups.forEach { consul ->
            val consulContainerNames = consulSetups.map { it.container.containerName() }.toTypedArray()
            val args = arrayOf("consul", "join", *consulContainerNames)
            consul.container.execInContainer(*args)
        }
    }

    fun joinWith(other: ConsulClusterSetup) {
        consulSetups.forEach { consul ->
            val consulInDc2ContainerNames = other.consulSetups.map { it.container.containerName() }.toTypedArray()
            val args = arrayOf("consul", "join", "-wan", *consulInDc2ContainerNames)
            consul.container.execInContainer(*args)
        }
    }
}
