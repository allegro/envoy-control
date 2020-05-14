package pl.allegro.tech.servicemesh.envoycontrol.services

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class MultiClusterStateTest {

    @Test
    fun `MultiCLusterStates should implement equality`() {
        // given
        val serviceInstance1 = ServiceInstance("1", address = "0.0.0.0", port = 1, tags = setOf("a"))
        val serviceInstance2 = ServiceInstance("1", address = "0.0.0.0", port = 1, tags = setOf("a"))
        val serviceInstances1 = ServiceInstances("a", setOf(serviceInstance1))
        val serviceInstances2 = ServiceInstances("a", setOf(serviceInstance2))
        val state1 = listOf(ClusterState(ServicesState(mapOf("a" to serviceInstances1)), Locality.REMOTE, "dc1"))
        val state2 = listOf(ClusterState(ServicesState(mapOf("a" to serviceInstances2)), Locality.REMOTE, "dc1"))
        val multiClusterState1 = MultiClusterState(state1)
        val multiClusterState2 = MultiClusterState(state2)

        // then
        assertThat(multiClusterState1).isEqualTo(multiClusterState2)
    }
}
