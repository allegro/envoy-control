package pl.allegro.tech.servicemesh.envoycontrol.reliability

import eu.rekawek.toxiproxy.Proxy
import org.testcontainers.junit.jupiter.Testcontainers
import pl.allegro.tech.servicemesh.envoycontrol.config.BaseEnvoyTest.Companion.consul
import pl.allegro.tech.servicemesh.envoycontrol.config.BaseEnvoyTest.Companion.network
import pl.allegro.tech.servicemesh.envoycontrol.config.containers.ToxiproxyContainer
import pl.allegro.tech.servicemesh.envoycontrol.config.containers.ToxiproxyContainer.Companion.internalToxiproxyPort
import pl.allegro.tech.servicemesh.envoycontrol.config.testcontainers.GenericContainer.Companion.allInterfaces
import pl.allegro.tech.servicemesh.envoycontrol.utils.Ports

@Testcontainers
internal class Toxiproxy private constructor() {
    companion object {
        val toxiproxyGrpcPort = Ports.nextAvailable()
        val toxiproxyGrpcPort2 = Ports.nextAvailable()
        val ec1HttpPort = Ports.nextAvailable()
        val ec2HttpPort = Ports.nextAvailable()
        private const val internalConsulProxyPort = 1337
        private const val internalEnvoyControl1GrpcPort = 1338
        private const val internalEnvoyControl2GrpcPort = 1339
        private const val internalEnvoyControl1HttpPort = 1340
        private const val internalEnvoyControl2HttpPort = 1341

        val toxiContainer: ToxiproxyContainer = ToxiproxyContainer()
            .withNetwork(network)
            .withExposedPorts(
                internalToxiproxyPort,
                internalConsulProxyPort,
                internalEnvoyControl1GrpcPort,
                internalEnvoyControl2GrpcPort,
                internalEnvoyControl1HttpPort,
                internalEnvoyControl2HttpPort
            )

        init {
            toxiContainer.start()
        }
        private val client = toxiContainer.client

        val consulProxy: Proxy = client.createProxy(
            "consul",
            "$allInterfaces:$internalConsulProxyPort",
            "${consul.ipAddress()}:${consul.internalPort}"
        )
        val externalConsulPort: Int = toxiContainer.getMappedPort(internalConsulProxyPort)
        val externalEnvoyControl1GrpcPort: Int = toxiContainer.getMappedPort(internalEnvoyControl1GrpcPort)
        val externalEnvoyControl2GrpcPort: Int = toxiContainer.getMappedPort(internalEnvoyControl2GrpcPort)
        val externalEnvoyControl1HttpPort: Int = toxiContainer.getMappedPort(internalEnvoyControl1HttpPort)
        val externalEnvoyControl2HttpPort: Int = toxiContainer.getMappedPort(internalEnvoyControl2HttpPort)

        val envoyControl1Proxy: Proxy = client.createProxy(
            "envoyControl1",
            "$allInterfaces:$internalEnvoyControl1GrpcPort",
            "${toxiContainer.hostIp()}:$toxiproxyGrpcPort"
        )

        val envoyControl2Proxy: Proxy = client.createProxy(
            "envoyControl2",
            "$allInterfaces:$internalEnvoyControl2GrpcPort",
            "${toxiContainer.hostIp()}:$toxiproxyGrpcPort2"
        )

        val envoyControl1HttpProxy: Proxy = client.createProxy(
            "ec1ToEc2",
            "$allInterfaces:$internalEnvoyControl1HttpPort",
            "${toxiContainer.hostIp()}:$ec1HttpPort"
        )

        val envoyControl2HttpProxy: Proxy = client.createProxy(
            "ec2ToEc1",
            "$allInterfaces:$internalEnvoyControl2HttpPort",
            "${toxiContainer.hostIp()}:$ec2HttpPort"
        )
    }
}
