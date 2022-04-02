package pl.allegro.tech.servicemesh.envoycontrol.services

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class MultiClusterStateTest {

    @Test
    fun `MultiClusterStates should implement equality`() {
        // given
        val multiClusterState1 = createMultiClusterState()
        val multiClusterState2 = createMultiClusterState()

        // then
        assertThat(multiClusterState1).isEqualTo(multiClusterState2)
    }

    private fun createMultiClusterState(): MultiClusterState {
        val serviceInstance = ServiceInstance("1", address = "0.0.0.0", port = 1, tags = setOf("a"))
        val serviceInstances = ServiceInstances("a", setOf(serviceInstance))
        val state = listOf(ClusterState(ServicesState(mutableMapOf("a" to serviceInstances)), Locality.REMOTE, "dc1"))
        return MultiClusterState(state)
    }
}
