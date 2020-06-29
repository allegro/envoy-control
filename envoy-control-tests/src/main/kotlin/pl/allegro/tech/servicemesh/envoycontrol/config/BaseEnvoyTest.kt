package pl.allegro.tech.servicemesh.envoycontrol.config

import org.awaitility.Duration
import org.testcontainers.containers.Network
import org.testcontainers.junit.jupiter.Testcontainers
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulClientConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulContainer
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulOperations
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulServerConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulSetup
import pl.allegro.tech.servicemesh.envoycontrol.config.containers.EchoContainer
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyContainer
import pl.allegro.tech.servicemesh.envoycontrol.config.testcontainers.GenericContainer
import java.io.File
import java.lang.Thread.sleep
import java.util.UUID
import java.util.concurrent.TimeUnit

@Testcontainers
open class BaseEnvoyTest {
    companion object {
        val defaultDuration = Duration(90, TimeUnit.SECONDS)
        val network: Network = Network.newNetwork()

        val echoContainer: EchoContainer = EchoContainer()
        val echoContainer2: EchoContainer = EchoContainer()

        val consulMastersInDc1 = listOf(
            ConsulSetup(network, ConsulServerConfig(1, "dc1")),
            ConsulSetup(network, ConsulServerConfig(2, "dc1")),
            ConsulSetup(network, ConsulServerConfig(3, "dc1"))
        )

        val consulMastersInDc2 = listOf(
            ConsulSetup(network, ConsulServerConfig(1, "dc2")),
            ConsulSetup(network, ConsulServerConfig(2, "dc2")),
            ConsulSetup(network, ConsulServerConfig(3, "dc2"))
        )

        var consulAgentInDc1: ConsulSetup
        var lowRpcConsulClient: ConsulSetup

        val consulOperationsInFirstDc = consulMastersInDc1[0].consulOperations
        val consulOperationsInSecondDc = consulMastersInDc2[0].consulOperations
        val consulHttpPort = consulMastersInDc1[0].port
        val consul2HttpPort = consulMastersInDc2[0].port
        val consul: ConsulContainer = consulMastersInDc1[0].container

        init {
            echoContainer.start()
            echoContainer2.start()
            setupMultiDcConsul()
            consulAgentInDc1 = ConsulSetup(network, ConsulClientConfig(1, "dc1", consul.ipAddress()))
            consulAgentInDc1.container.start()
            lowRpcConsulClient = setupLowRpcConsulClient()
        }

        private fun setupLowRpcConsulClient(): ConsulSetup {
            val client = ConsulSetup(
                network,
                ConsulClientConfig(
                    id = 2,
                    dc = "dc1",
                    serverAddress = consul.ipAddress(),
                    jsonFiles = listOf(File("testcontainers/consul-low-rpc-rate.json"))
                )
            )
            client.container.start()
            return client
        }

        private fun setupMultiDcConsul() {
            startConsulCluster(consulMastersInDc1)
            startConsulCluster(consulMastersInDc2)
            joinClusters(consulMastersInDc1, consulMastersInDc2)
        }

        private fun joinClusters(consulsInDc1: List<ConsulSetup>, consulsInDc2: List<ConsulSetup>) {
            consulsInDc1.forEach { consul ->
                val consulInDc2ContainerNames = consulsInDc2.map { it.container.containerName() }.toTypedArray()
                val args = arrayOf("consul", "join", "-wan", *consulInDc2ContainerNames)
                consul.container.execInContainer(*args)
            }
        }

        private fun startConsulCluster(consuls: List<ConsulSetup>) {
            consuls.forEach { consul ->
                consul.container.start()
            }
            consuls.forEach { consul ->
                val consulContainerNames = consuls.map { it.container.containerName() }.toTypedArray()
                val args = arrayOf("consul", "join", *consulContainerNames)
                consul.container.execInContainer(*args)
            }
        }

        fun registerServiceWithEnvoyOnIngress(name: String, envoy: EnvoyContainer, tags: List<String>) = registerService(
            name = name,
            container = envoy,
            port = EnvoyContainer.INGRESS_LISTENER_CONTAINER_PORT,
            tags = tags
        )

        fun registerService(
            id: String,
            name: String,
            address: String,
            port: Int,
            consulOps: ConsulOperations = consulOperationsInFirstDc
        ): String = consulOps.registerService(
            id = id,
            name = name,
            address = address,
            port = port
        )

        fun registerService(
            id: String = UUID.randomUUID().toString(),
            name: String,
            container: GenericContainer<*> = echoContainer,
            port: Int = EchoContainer.PORT,
            consulOps: ConsulOperations = consulOperationsInFirstDc,
            registerDefaultCheck: Boolean = false,
            tags: List<String> = listOf("a")
        ): String {
            return consulOps.registerService(
                id = id,
                name = name,
                address = container.ipAddress(),
                port = port,
                registerDefaultCheck = registerDefaultCheck,
                tags = tags
            )
        }

        fun registerServiceInRemoteDc(name: String, target: EchoContainer = echoContainer): String {
            return registerService(
                id = UUID.randomUUID().toString(),
                name = name,
                container = target,
                consulOps = consulOperationsInSecondDc
            )
        }

        fun deregisterService(id: String, consulOps: ConsulOperations = consulOperationsInFirstDc) {
            consulOps.deregisterService(id)
        }

        fun deregisterServiceInRemoteDc(id: String) {
            consulOperationsInSecondDc.deregisterService(id)
        }

        fun deregisterAllServices() {
            consulOperationsInFirstDc.deregisterAll()
            consulOperationsInSecondDc.deregisterAll()
            consulAgentInDc1.consulOperations.deregisterAll()
            sleep(1000) // todo remove it?
        }
    }
}
