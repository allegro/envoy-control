package pl.allegro.tech.servicemesh.envoycontrol.services

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class MultiZoneStateTest {

    @Test
    fun `MultiZoneStates should implement equality`() {
        // given
        val multiZoneState1 = createMultiZoneState()
        val multiZoneState2 = createMultiZoneState()

        // then
        assertThat(multiZoneState1).isEqualTo(multiZoneState2)
    }

    private fun createMultiZoneState(): MultiZoneState {
        val serviceInstance = ServiceInstance("1", address = "0.0.0.0", port = 1, tags = setOf("a"))
        val serviceInstances = ServiceInstances("a", setOf(serviceInstance))
        val state = listOf(ZoneState(ServicesState(mapOf("a" to serviceInstances)), Locality.REMOTE, "dc1"))
        return MultiZoneState(state)
    }
}
