package pl.allegro.tech.servicemesh.envoycontrol.snapshot.endpoints

import io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.services.ClusterState
import pl.allegro.tech.servicemesh.envoycontrol.services.Locality
import pl.allegro.tech.servicemesh.envoycontrol.services.MultiClusterState
import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceInstance
import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceInstances
import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceName
import pl.allegro.tech.servicemesh.envoycontrol.services.ServicesState
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.LoadBalancingPriorityProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.LoadBalancingProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.endpoints.EnvoyEndpointsFactory
import java.util.concurrent.ConcurrentHashMap

class EnvoyEndpointsFactoryTest {
    private val serviceName = "service-one"
    private val multiClusterState = MultiClusterState(
        listOf(
            clusterState(Locality.LOCAL, "DC1"),
            clusterState(Locality.REMOTE, "DC2"),
            clusterState(Locality.REMOTE, "DC3")
        )
    )

    @Test
    fun `should create load assignment with enabled lb priorities`() {
        val snapshotProperties = SnapshotProperties().apply {
            loadBalancing = LoadBalancingProperties()
                .apply {
                    priorities = LoadBalancingPriorityProperties().apply {
                        enabled = true
                        zonePriorities = mapOf(
                            "DC1" to 0,
                            "DC2" to 1,
                            "DC3" to 2
                        )
                    }
                }
        }

        val envoyEndpointsFactory = EnvoyEndpointsFactory(snapshotProperties)
        val loadAssignments = envoyEndpointsFactory.createLoadAssignment(setOf(serviceName), multiClusterState)

        assertThat(loadAssignments)
            .isNotEmpty()
            .anySatisfy { loadAssignment ->
                assertThat(loadAssignment.endpointsList)
                    .isNotNull()
                    .anySatisfy { it.hasZoneWithPriority(0, "DC1") }
                    .anySatisfy { it.hasZoneWithPriority(1, "DC2") }
                    .anySatisfy { it.hasZoneWithPriority(2, "DC3") }
            }
    }

    @Test
    fun `should create default load assignment having lb priorities config disabled`() {
        val serviceName = "service-one"
        val snapshotProperties = SnapshotProperties().apply {
            loadBalancing = LoadBalancingProperties().apply {
                priorities = LoadBalancingPriorityProperties().apply {
                    enabled = false
                }
            }
        }

        val envoyEndpointsFactory = EnvoyEndpointsFactory(snapshotProperties)
        val loadAssignments = envoyEndpointsFactory.createLoadAssignment(setOf(serviceName), multiClusterState)

        assertThat(loadAssignments)
            .isNotEmpty()
            .anySatisfy { loadAssignment ->
                assertThat(loadAssignment.endpointsList)
                    .isNotNull()
                    .anySatisfy { it.hasZoneWithPriority(0, "DC1") }
                    .anySatisfy { it.hasZoneWithPriority(1, "DC2") }
                    .anySatisfy { it.hasZoneWithPriority(1, "DC3") }
            }
    }

    @Test
    fun `should create default load assignment having enabled misconfigured lb priorities`() {
        val serviceName = "service-one"
        val snapshotProperties = SnapshotProperties().apply {
            loadBalancing = LoadBalancingProperties()
                .apply {
                    priorities = LoadBalancingPriorityProperties().apply {
                        enabled = true
                        zonePriorities = mapOf()
                    }
                }
        }

        val envoyEndpointsFactory = EnvoyEndpointsFactory(snapshotProperties)
        val loadAssignments = envoyEndpointsFactory.createLoadAssignment(setOf(serviceName), multiClusterState)

        assertThat(loadAssignments)
            .isNotEmpty()
            .anySatisfy { loadAssignment ->
                assertThat(loadAssignment.endpointsList)
                    .isNotNull()
                    .anySatisfy { it.hasZoneWithPriority(0, "DC1") }
                    .anySatisfy { it.hasZoneWithPriority(1, "DC2") }
                    .anySatisfy { it.hasZoneWithPriority(1, "DC3") }
            }
    }

    private fun clusterState(locality: Locality, cluster: String): ClusterState {
        return ClusterState(
            ServicesState(
                serviceNameToInstances = concurrentMapOf(
                    serviceName to ServiceInstances(
                        serviceName, setOf(
                            ServiceInstance(
                                id = "id",
                                tags = setOf("envoy"),
                                address = "127.0.0.3",
                                port = 4444
                            )
                        )
                    )
                )
            ),
            locality, cluster
        )
    }

    private fun concurrentMapOf(vararg elements: Pair<ServiceName, ServiceInstances>): ConcurrentHashMap<ServiceName, ServiceInstances> {
        val state = ConcurrentHashMap<ServiceName, ServiceInstances>()
        elements.forEach { (name, instance) -> state[name] = instance }
        return state
    }

    private fun LocalityLbEndpoints.hasZoneWithPriority(priority: Int, zone: String) {
        assertThat(this.priority).isEqualTo(priority)
        assertThat(this.locality.zone).isEqualTo(zone)
    }
}
