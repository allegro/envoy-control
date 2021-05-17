package pl.allegro.tech.servicemesh.envoycontrol.reliability

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlRunnerTestApp
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulSetup
import pl.allegro.tech.servicemesh.envoycontrol.reliability.Toxiproxy.Companion.ec1HttpPort
import pl.allegro.tech.servicemesh.envoycontrol.reliability.Toxiproxy.Companion.ec2HttpPort
import pl.allegro.tech.servicemesh.envoycontrol.reliability.Toxiproxy.Companion.externalEnvoyControl1HttpPort
import pl.allegro.tech.servicemesh.envoycontrol.reliability.Toxiproxy.Companion.externalEnvoyControl2HttpPort

class DcCutOffTest : ReliabilityTest() {
    companion object {

        private val properties = mapOf(
            "envoy-control.sync.enabled" to true
        )

        @JvmStatic
        @BeforeAll
        fun setup() {
            setup(
                envoyControls = 2,
                appFactoryForEc1 = {
                    EnvoyControlRunnerTestApp(
                        consulPort = consulHttpPort,
                        propertiesProvider = { properties },
                        appPort = ec1HttpPort
                    )
                },
                appFactoryForEc2 = {
                    EnvoyControlRunnerTestApp(
                        consulPort = consul2HttpPort,
                        appPort = ec2HttpPort
                    )
                },
                ec1RegisterPort = externalEnvoyControl1HttpPort,
                ec2RegisterPort = externalEnvoyControl2HttpPort
            )
        }
    }

    @Test
    fun `should be resilient to transient unavailability of one DC`() {
        // given
        val id = registerServiceInRemoteCluster("echo", echoContainer)

        // then
        waitUntilEchoCalledThroughEnvoyResponds(echoContainer)

        // when
        cutOffConnectionBetweenDCs()
        makeChangesInRemoteDcAsynchronously(id)

        // then
        holdAssertionsTrue {
            assertUnreachableThroughEnvoy("echo")
        }

        // when
        restoreConnectionBetweenDCs()

        // then
        waitUntilEchoCalledThroughEnvoyResponds(echoContainer2)
    }

    private fun makeChangesInRemoteDcAsynchronously(id: String) {
        Thread {
            // if failureDuration is Duration(1, SECONDS).divide(2) then Duration(0, SECONDS)
            Thread.sleep(failureDuration.dividedBy(2L).toMillis())
            deregisterServiceInRemoteDc(id)
            registerServiceInRemoteCluster("echo", echoContainer2)
        }.start()
    }

    private fun cutOffConnectionBetweenDCs() {
        cutOffConnectionBetweenECs()
        cutOffConnectionBetweenConsuls()
        cutOffConnectionToServicesInDc2()
    }

    private fun cutOffConnectionBetweenConsuls() {
        blockConsulTraffic(consulMastersInDc1, consulMastersInDc2)
        blockConsulTraffic(consulMastersInDc2, consulMastersInDc1)
    }

    private fun cutOffConnectionToServicesInDc2() {
        envoyContainer1.blockTrafficTo(echoContainer.ipAddress())
        envoyContainer1.blockTrafficTo(echoContainer2.ipAddress())
    }

    private fun restoreConnectionToServicesInDc2() {
        envoyContainer1.unblockTrafficTo(echoContainer.ipAddress())
        envoyContainer1.unblockTrafficTo(echoContainer2.ipAddress())
    }

    private fun blockConsulTraffic(from: List<ConsulSetup>, to: List<ConsulSetup>) {
        modifyConnection(to, from, ModifyConnection.BLOCK)
    }

    private fun restoreConsulTraffic(from: List<ConsulSetup>, to: List<ConsulSetup>) {
        modifyConnection(to, from, ModifyConnection.RESTORE)
    }

    private enum class ModifyConnection { BLOCK, RESTORE }

    private fun modifyConnection(
        to: List<ConsulSetup>,
        from: List<ConsulSetup>,
        operation: ModifyConnection
    ) {
        val peers = to[0].operations.peers().map { ip -> ip.split(":")[0] }
        peers.forEach { ip ->
            from.forEach { consul ->
                if (operation == ModifyConnection.BLOCK) {
                    consul.container.blockTrafficTo(ip)
                } else if (operation == ModifyConnection.RESTORE) {
                    consul.container.unblockTrafficTo(ip)
                }
            }
        }
    }

    private fun restoreConnectionBetweenDCs() {
        restoreConnectionBetweenECs()
        restoreConnectionBetweenConsuls()
        restoreConnectionToServicesInDc2()
        // TODO: https://github.com/allegro/envoy-control/issues/8
        // consul master has problem to reconnect to dc2 and container restart helps
        consulMastersInDc1[0].container.restart()
    }

    private fun restoreConnectionBetweenConsuls() {
        restoreConsulTraffic(consulMastersInDc1, consulMastersInDc2)
        restoreConsulTraffic(consulMastersInDc2, consulMastersInDc1)
    }
}
