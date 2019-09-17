package pl.allegro.tech.servicemesh.envoycontrol.reliability

import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.reliability.Toxiproxy.Companion.consulProxy

internal class LocalConsulAgentDownTest : ReliabilityTest() {
    @Test
    fun `is resilient to transient unavailability of EC's local Consul agent`() {
        // given
        registerService(name = "service-1")
        assertReachableThroughEnvoy("service-1")

        // when
        makeConsulUnavailable()
        // Service registration is not affected by injected Consul faults, it bypasses toxiproxy
        registerService(name = "service-2")

        // then
        holdAssertionsTrue {
            assertReachableThroughEnvoy("service-1")
            assertUnreachableThroughEnvoy("service-2")
        }

        // and when
        makeConsulAvailable()

        // then
        assertReachableThroughEnvoy("service-1")
        assertReachableThroughEnvoy("service-2")
    }

    @Test
    fun `is resilient to transient unavailability of target service's local Consul agent`() {
        // given
        registerService(name = "echo", container = echoContainer, consulOps = consulAgentInDc1.consulOperations, registerDefaultCheck = true)
        // then
        assertReachableThroughEnvoy("echo")

        // when
        makeServiceConsulAgentUnavailable()
        // then
        holdAssertionsTrue {
            assertUnreachableThroughEnvoy("echo")
        }

        // when
        makeServiceConsulAgentAvailable()

        // then
        assertReachableThroughEnvoy("echo")
    }

    private fun makeServiceConsulAgentAvailable() {
        consulAgentInDc1.container.unblockExternalTraffic()
    }

    private fun makeServiceConsulAgentUnavailable() {
        consulAgentInDc1.container.blockExternalTraffic()
    }

    private fun makeConsulAvailable() {
        consulProxy.enable()
    }

    private fun makeConsulUnavailable() {
        consulProxy.disable()
    }
}
