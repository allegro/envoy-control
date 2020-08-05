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
