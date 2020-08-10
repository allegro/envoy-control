package pl.allegro.tech.servicemesh.envoycontrol.reliability

import org.junit.jupiter.api.Test

internal class LocalConsulAgentToMasterCutOff : ReliabilityTest() {

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
