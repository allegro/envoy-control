package pl.allegro.tech.servicemesh.envoycontrol.services

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap

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
        val services = ConcurrentHashMap<ServiceName, ServiceInstances>()
        services["a"] = serviceInstances
        val state = listOf(ClusterState(ServicesState(services), Locality.REMOTE, "dc1"))
        return MultiClusterState(state)
    }
}
