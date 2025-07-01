package pl.allegro.tech.servicemesh.envoycontrol.reliability

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlRunnerTestApp

internal class LocalConsulAgentToMasterCutOff : ReliabilityTest() {
    companion object {
        @JvmStatic
        @BeforeAll
        fun setup() {
            setup(
                appFactoryForEc1 = {
                    EnvoyControlRunnerTestApp(
                        consulPort = Toxiproxy.externalConsulPort,
                        grpcPort = Toxiproxy.toxiproxyGrpcPort
                    )
                },
                envoyConnectGrpcPort = Toxiproxy.externalEnvoyControl1GrpcPort
            )
        }
    }

    @Test
    fun `should register service when communication between local agent and master is restored`() {
        // given
        registerService(name = "service-1")
        assertReachableThroughEnvoy("service-1")
        assertUnreachableThroughEnvoy("service-2")

        // when
        consulMastersInDc1.forEach {
            it.container.blockExternalTraffic()
        }

        // and
        registerService(name = "service-2", consulOps = consulAgentInDc1.operations)

        // then
        holdAssertionsTrue {
            assertReachableThroughEnvoy("service-1")
            assertUnreachableThroughEnvoy("service-2")
        }

        // when
        consulMastersInDc1.forEach {
            it.container.unblockExternalTraffic()
        }

        // then
        holdAssertionsTrue {
            assertReachableThroughEnvoy("service-1")
            assertReachableThroughEnvoy("service-2")
        }
    }
}
