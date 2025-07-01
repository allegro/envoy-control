package pl.allegro.tech.servicemesh.envoycontrol.reliability

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlRunnerTestApp

internal class NoConsulLeaderTest : ReliabilityTest() {
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
    fun `is resilient to consul cluster without a leader`() {
        // given
        registerService(name = "service-1")
        assertReachableThroughEnvoy("service-1")

        // when
        makeConsulClusterLoseLeader()
        assertConsulHasNoLeader()

        registerService(name = "service-2")

        // then
        holdAssertionsTrue {
            assertConsulHasNoLeader()
            assertReachableThroughEnvoy("service-1")
            assertUnreachableThroughEnvoy("service-2")
        }

        // when
        makeConsulClusterRegainLeader()

        // then
        assertConsulHasALeader()
        assertReachableThroughEnvoy("service-1")
        assertReachableThroughEnvoy("service-2")
    }

    private fun makeConsulClusterRegainLeader() {
        consulMastersInDc1.drop(1).forEach { consul ->
            consul.container.sigcont()
        }
    }

    private fun makeConsulClusterLoseLeader() {
        consulMastersInDc1.drop(1).forEach { consul ->
            consul.container.sigstop()
        }
    }
}
