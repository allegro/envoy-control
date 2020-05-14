package pl.allegro.tech.servicemesh.envoycontrol.services

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class MultiClusterStateTest {

    @Test
    fun `MultiCLusterStates should implement equality`() {
        // given
        val element = ServiceInstance("1", address = "0.0.0.0", port = 1, tags = setOf("a"))
        val serviceInstances = ServiceInstances("a", setOf(element))
        val l = listOf(ClusterState(ServicesState(mapOf("a" to serviceInstances)), Locality.REMOTE, "dc1"))
        val multiClusterState1 = MultiClusterState(l)
        val multiClusterState2 = MultiClusterState(l)

        // then
        assertThat(multiClusterState1).isEqualTo(multiClusterState2)
    }
}
