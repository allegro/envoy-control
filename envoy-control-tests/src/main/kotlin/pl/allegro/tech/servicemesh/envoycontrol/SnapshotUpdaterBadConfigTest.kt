package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.RegisterExtension
import org.testcontainers.containers.ContainerLaunchException
import org.testcontainers.containers.Network
import org.testcontainers.containers.startupcheck.IndefiniteWaitOneShotStartupCheckStrategy
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isFrom
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isOk
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.FaultyConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyContainer
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension

class SnapshotUpdaterBadConfigTest {
    companion object {

        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(consul, mapOf(
                "envoy-control.envoy.snapshot.incoming-permissions.enabled" to true
            )
        )

        @JvmField
        @RegisterExtension
        val service = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val redeployedService = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val envoy = EnvoyExtension(envoyControl, service)
    }

    @Test
    fun `should not crash on a badly configured client`() {
        // given
        val id = consul.server.operations.registerService(service, name = "echo")
        untilAsserted {
            // when
            val response = envoy.egressOperations.callService(service = "echo")

            // then
            assertThat(response).isOk().isFrom(service)
        }

        // when
        assertThrows<ContainerLaunchException> {
            tryConnectingBadEnvoy()
        }

        // then
        checkTrafficIsRoutedToServiceAfterItsRedeploy(id)
    }

    private fun tryConnectingBadEnvoy() {
        EnvoyContainer(
            FaultyConfig,
            { service.container().ipAddress() },
            envoyControl.app.grpcPort
        )
            .withNetwork(Network.SHARED)
            .withStartupCheckStrategy(IndefiniteWaitOneShotStartupCheckStrategy())
            .start()
    }

    private fun checkTrafficIsRoutedToServiceAfterItsRedeploy(id: String) {
        // given
        // we first register a new instance and then remove other to maintain cluster presence in Envoy
        consul.server.operations.registerService(redeployedService, name = "echo")
        waitForEchoServices(instances = 2)

        consul.server.operations.deregisterService(id)
        waitForEchoServices(instances = 1)

        untilAsserted {
            // when
            val response = envoy.egressOperations.callService(service = "echo")

            // then
            assertThat(response).isOk().isFrom(redeployedService)
        }
    }

    private fun waitForEchoServices(instances: Int) {
        untilAsserted {
            assertThat(envoy.container.admin().numOfEndpoints(clusterName = "echo"))
                .isEqualTo(instances)
        }
    }
}
