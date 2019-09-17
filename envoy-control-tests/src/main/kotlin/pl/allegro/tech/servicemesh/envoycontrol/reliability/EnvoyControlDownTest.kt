package pl.allegro.tech.servicemesh.envoycontrol.reliability

import org.junit.jupiter.api.Test

internal class EnvoyControlDownTest : ReliabilityTest() {

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
