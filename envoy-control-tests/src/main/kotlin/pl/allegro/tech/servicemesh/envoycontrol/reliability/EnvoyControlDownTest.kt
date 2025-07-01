package pl.allegro.tech.servicemesh.envoycontrol.reliability

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlRunnerTestApp

internal class EnvoyControlDownTest : ReliabilityTest() {

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
    fun `is resilient to EnvoyControl failure in one dc`() {
        // given
        registerService(name = "service-1")
        assertReachableThroughEnvoy("service-1")

        // when
        makeEnvoyControlUnavailable()

        // Service registration is not affected by injected Consul faults, it bypasses toxiproxy
        registerService(name = "service-2")

        // then
        holdAssertionsTrue {
            assertReachableThroughEnvoy("service-1")
            assertUnreachableThroughEnvoy("service-2")
        }

        // and when
        makeEnvoyControlAvailable()

        // then
        assertReachableThroughEnvoy("service-1")
        assertReachableThroughEnvoy("service-2")
    }
}
