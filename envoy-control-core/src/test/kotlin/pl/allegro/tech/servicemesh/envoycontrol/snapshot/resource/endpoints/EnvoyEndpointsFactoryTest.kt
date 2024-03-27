package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.endpoints

import com.google.protobuf.util.JsonFormat
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment
import io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import pl.allegro.tech.servicemesh.envoycontrol.groups.DependencySettings
import pl.allegro.tech.servicemesh.envoycontrol.groups.RoutingPolicy
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
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.TrafficSplittingProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.WeightRouteSpecification
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.ZoneWeights
import java.util.concurrent.ConcurrentHashMap
import java.util.stream.Stream

internal class EnvoyEndpointsFactoryTest {

    companion object {
        private val dcPriorityProperties = mapOf(
            "DC1" to mapOf(
                "DC1" to 0,
                "DC2" to 1,
                "DC3" to 2
            ),
            "DC2" to mapOf(
                "DC1" to 1,
                "DC2" to 0,
                "DC3" to 2
            ),
            "DC3" to mapOf(
                "DC1" to 1,
                "DC2" to 2,
                "DC3" to 0
            )
        )

        @JvmStatic
        fun dcToPriorities(): Stream<Arguments> = dcPriorityProperties.entries
            .stream()
            .map { Arguments.of(it.key, it.value) }
    }

    private val serviceName = "service-one"

    private val defaultZone = "DC1"
    private val trafficSplittingZone = "DC2"

    private val endpointsFactory = EnvoyEndpointsFactory(
        SnapshotProperties().apply {
            routing.serviceTags.enabled = true
            routing.serviceTags.autoServiceTagEnabled = true
        },
        currentZone = defaultZone
    )

    private val multiClusterStateDC1Local = MultiClusterState(
        listOf(
            clusterState(Locality.LOCAL, "DC1"),
            clusterState(Locality.REMOTE, "DC2"),
            clusterState(Locality.REMOTE, "DC3")
        )
    )

    private val multiClusterStateDC2Local = MultiClusterState(
        listOf(
            clusterState(Locality.REMOTE, "DC1"),
            clusterState(Locality.LOCAL, "DC2"),
            clusterState(Locality.REMOTE, "DC3")
        )
    )

    private val defaultZoneWeights = mapOf(
        "DC1" to 100,
        "DC2" to 10,
        "DC3" to 2
    )

    private val snapshotPropertiesWithWeights = SnapshotProperties().apply {
        loadBalancing = LoadBalancingProperties()
            .apply {
                priorities = LoadBalancingPriorityProperties()
                    .apply { zonePriorities = dcPriorityProperties }
                trafficSplitting = TrafficSplittingProperties()
                    .apply {
                        zoneName = trafficSplittingZone
                        zonesAllowingTrafficSplitting = listOf("DC1")
                        weightsByService = mapOf(
                            serviceName to ZoneWeights()
                                .apply {
                                    weightByZone = defaultZoneWeights
                                }
                        )
                    }
            }
    }

    // language=json
    private val globalLoadAssignmentJson = """{
      "cluster_name": "lorem-service",
      "endpoints": [
        {
          "locality": { "zone": "west" },
          "lb_endpoints": [
            {
              "endpoint": { "address": { "socket_address": { "address": "1.2.3.4", "port_value": 111 } } },
              "metadata": { "filter_metadata": {
                "envoy.lb": { "canary": "1", "tag": [ "x64", "lorem" ] },
                "envoy.transport_socket_match": {}
              }},
              "load_balancing_weight": 0
            },
            {
              "endpoint": { "address": { "socket_address": { "address": "2.3.4.5", "port_value": 222 } } },
              "metadata": { "filter_metadata": {
                "envoy.lb": { "lb_regular": true, "tag": ["global"] },
                "envoy.transport_socket_match": {}
              }},
              "load_balancing_weight": 50
            },
            {
              "endpoint": { "address": { "socket_address": { "address": "3.4.5.6", "port_value": 333 } } },
              "metadata": { "filter_metadata": {
                "envoy.lb": { "lb_regular": true, "tag": ["lorem"] },
                "envoy.transport_socket_match": { "acceptMTLS": true }
              }},
              "load_balancing_weight": 40
            }
          ] 
        },
        {
          "locality": { "zone": "east" },
          "lb_endpoints": [
            {
              "endpoint": { "address": { "socket_address": { "address": "4.5.6.7", "port_value": 444 } } },
              "metadata": { "filter_metadata": {
                "envoy.lb": { "lb_regular": true, "tag": ["lorem", "ipsum"] },
                "envoy.transport_socket_match": { "acceptMTLS": true }
              }},
              "load_balancing_weight": 60
            }
          ],
          "priority": 1
        },
        {
          "locality": { "zone": "south" },
          "priority": 1
        }
      ]
    }"""
    private val globalLoadAssignment = globalLoadAssignmentJson.toClusterLoadAssignment()

    @Test
    fun `should not filter endpoints if auto service tags are disabled`() {
        // given
        val policy = RoutingPolicy(autoServiceTag = false)

        // when
        val filtered = endpointsFactory.filterEndpoints(globalLoadAssignment, policy)

        // then
        assertThat(filtered)
            .isEqualTo(globalLoadAssignmentJson.toClusterLoadAssignment())
            .describedAs("unnecessary copy!").isSameAs(globalLoadAssignment)
    }

    @Test
    fun `should filter lorem endpoints from two localities and reuse objects in memory`() {
        // given
        val policy = RoutingPolicy(autoServiceTag = true, serviceTagPreference = listOf("lorem"))
        // language=json
        val expectedLoadAssignmentJson = """{
          "cluster_name": "lorem-service",
          "endpoints": [
            {
              "locality": { "zone": "west" },
              "lb_endpoints": [
                {
                  "endpoint": { "address": { "socket_address": { "address": "1.2.3.4", "port_value": 111 } } },
                  "metadata": { "filter_metadata": {
                    "envoy.lb": { "canary": "1", "tag": [ "x64", "lorem" ] },
                    "envoy.transport_socket_match": {}
                  }},
                  "load_balancing_weight": 0
                },
                {
                  "endpoint": { "address": { "socket_address": { "address": "3.4.5.6", "port_value": 333 } } },
                  "metadata": { "filter_metadata": {
                    "envoy.lb": { "lb_regular": true, "tag": ["lorem"] },
                    "envoy.transport_socket_match": { "acceptMTLS": true }
                  }},
                  "load_balancing_weight": 40
                }
              ] 
            },
            {
              "locality": { "zone": "east" },
              "lb_endpoints": [
                {
                  "endpoint": { "address": { "socket_address": { "address": "4.5.6.7", "port_value": 444 } } },
                  "metadata": { "filter_metadata": {
                    "envoy.lb": { "lb_regular": true, "tag": ["lorem", "ipsum"] },
                    "envoy.transport_socket_match": { "acceptMTLS": true }
                  }},
                  "load_balancing_weight": 60
                }
              ],
              "priority": 1
            }
          ]
        }"""

        // when
        val filtered = endpointsFactory.filterEndpoints(globalLoadAssignment, policy)

        // then
        assertThat(filtered)
            .isNotEqualTo(globalLoadAssignmentJson.toClusterLoadAssignment())
            .isEqualTo(expectedLoadAssignmentJson.toClusterLoadAssignment())

        val westEndpoints = filtered.getEndpoints(0).lbEndpointsList
        val globalWestEndpoints = globalLoadAssignment.getEndpoints(0).lbEndpointsList
        assertThat(westEndpoints[0]).describedAs("unnecessary copy!").isSameAs(globalWestEndpoints[0])
        assertThat(westEndpoints[1]).describedAs("unnecessary copy!").isSameAs(globalWestEndpoints[2])

        val eastEndpoints = filtered.getEndpoints(1)
        val globalEastEndpoints = globalLoadAssignment.getEndpoints(1)
        assertThat(eastEndpoints).describedAs("unnecessary copy!").isSameAs(globalEastEndpoints)

        val southEndpoints = filtered.getEndpoints(1)
        val globalSouthEndpoints = globalLoadAssignment.getEndpoints(1)
        assertThat(southEndpoints).describedAs("unnecessary copy!").isSameAs(globalSouthEndpoints)
    }

    @Test
    fun `should filter ipsum endpoints as fallback and reuse objects in memory`() {
        // given
        val policy = RoutingPolicy(autoServiceTag = true, serviceTagPreference = listOf("est", "ipsum"))
        // language=json
        val expectedLoadAssignmentJson = """{
          "cluster_name": "lorem-service",
          "endpoints": [
            {
              "locality": { "zone": "east" },
              "lb_endpoints": [
                {
                  "endpoint": { "address": { "socket_address": { "address": "4.5.6.7", "port_value": 444 } } },
                  "metadata": { "filter_metadata": {
                    "envoy.lb": { "lb_regular": true, "tag": ["lorem", "ipsum"] },
                    "envoy.transport_socket_match": { "acceptMTLS": true }
                  }},
                  "load_balancing_weight": 60
                }
              ],
              "priority": 1
            }
          ]
        }"""

        // when
        val filtered = endpointsFactory.filterEndpoints(globalLoadAssignment, policy)

        // then
        assertThat(filtered)
            .isNotEqualTo(globalLoadAssignmentJson.toClusterLoadAssignment())
            .isEqualTo(expectedLoadAssignmentJson.toClusterLoadAssignment())

        val eastEndpoints = filtered.getEndpoints(0)
        val globalEastEndpoints = globalLoadAssignment.getEndpoints(1)
        assertThat(eastEndpoints).describedAs("unnecessary copy!").isSameAs(globalEastEndpoints)

        val southEndpoints = filtered.getEndpoints(0)
        val globalSouthEndpoints = globalLoadAssignment.getEndpoints(1)
        assertThat(southEndpoints).describedAs("unnecessary copy!").isSameAs(globalSouthEndpoints)
    }

    @Test
    fun `should return all endpoints if preferred tag not found and fallback to any instance is true`() {
        // given
        val policy =
            RoutingPolicy(autoServiceTag = true, serviceTagPreference = listOf("est"), fallbackToAnyInstance = true)

        // when
        val filtered = endpointsFactory.filterEndpoints(globalLoadAssignment, policy)

        // then
        assertThat(filtered)
            .isEqualTo(globalLoadAssignmentJson.toClusterLoadAssignment())
            .describedAs("unnecessary copy!").isSameAs(globalLoadAssignment)
    }

    @Test
    fun `should return empty result if no matching instance is found`() {
        // given
        val policy = RoutingPolicy(autoServiceTag = true, serviceTagPreference = listOf("est"))
        // language=json
        val expectedLoadAssignmentJson = """{
          "cluster_name": "lorem-service"
        }"""

        // when
        val filtered = endpointsFactory.filterEndpoints(globalLoadAssignment, policy)

        // then
        assertThat(filtered)
            .isNotEqualTo(globalLoadAssignmentJson.toClusterLoadAssignment())
            .isEqualTo(expectedLoadAssignmentJson.toClusterLoadAssignment())
    }

    @ParameterizedTest
    @MethodSource("dcToPriorities")
    fun `should create load assignment according to properties having lb priorities enabled`(
        dcName: String, expectedConfig: Map<String, Int>
    ) {
        val envoyEndpointsFactory = EnvoyEndpointsFactory(
            snapshotPropertiesWithPriorities(dcPriorityProperties),
            currentZone = dcName
        )
        val loadAssignments = envoyEndpointsFactory.createLoadAssignment(setOf(serviceName), multiClusterStateDC1Local)
        loadAssignments.assertHasLoadAssignment(expectedConfig)
    }

    @Test
    fun `should create default load assignment having lb priorities disabled`() {
        val envoyEndpointsFactory = EnvoyEndpointsFactory(
            snapshotPropertiesWithPriorities(mapOf()),
            currentZone = "DC2"
        )
        val loadAssignments = envoyEndpointsFactory.createLoadAssignment(setOf(serviceName), multiClusterStateDC2Local)
        loadAssignments.assertHasLoadAssignment(
            mapOf(
                "DC1" to 1,
                "DC2" to 0,
                "DC3" to 1
            )
        )
    }

    @Test
    fun `should create default load assignment having misconfigured lb priorities for current zone`() {
        val envoyEndpointsFactory = EnvoyEndpointsFactory(
            snapshotPropertiesWithPriorities(
                mapOf("DC2" to mapOf())
            ),
            currentZone = "DC2"
        )
        val loadAssignments = envoyEndpointsFactory.createLoadAssignment(setOf(serviceName), multiClusterStateDC2Local)
        loadAssignments.assertHasLoadAssignment(
            mapOf(
                "DC1" to 1,
                "DC2" to 0,
                "DC3" to 1
            )
        )
    }

    @Test
    fun `should create load assignment having partially configured lb priorities for current zone`() {
        val envoyEndpointsFactory = EnvoyEndpointsFactory(
            snapshotPropertiesWithPriorities(
                mapOf("DC1" to mapOf("DC3" to 2))
            ),
            currentZone = "DC1"
        )
        val loadAssignments = envoyEndpointsFactory.createLoadAssignment(setOf(serviceName), multiClusterStateDC1Local)
        loadAssignments.assertHasLoadAssignment(
            mapOf(
                "DC1" to 0,
                "DC2" to 1,
                "DC3" to 2
            )
        )
    }

    @Test
    fun `should override priority and duplicate endpoints for traffic splitting zone`() {
        val envoyEndpointsFactory = EnvoyEndpointsFactory(
            snapshotPropertiesWithWeights,
            currentZone = "DC1"
        )
        val loadAssignments = envoyEndpointsFactory.createLoadAssignment(setOf(serviceName), multiClusterStateDC1Local)
        var resultLoadAssignment = envoyEndpointsFactory.assignLocalityWeights(
            WeightRouteSpecification(
                serviceName, listOf(), DependencySettings(), ZoneWeights().apply { weightByZone = defaultZoneWeights }
            ),
            loadAssignments[0]
        )

        assertThat(resultLoadAssignment.endpointsList).hasSize(dcPriorityProperties.size + 1)
        assertThat(resultLoadAssignment.endpointsList)
            .anySatisfy { it.hasZoneWithPriority("DC2", 1) }
            .anySatisfy { it.hasZoneWithPriority("DC2", 0) }
            .anySatisfy { it.hasZoneWithPriority("DC1", 0) }
            .anySatisfy { it.hasZoneWithPriority("DC3", 2) }
    }

    private fun List<ClusterLoadAssignment>.assertHasLoadAssignment(map: Map<String, Int>) {
        assertThat(this)
            .isNotEmpty()
            .anySatisfy { loadAssignment ->
                assertThat(loadAssignment.endpointsList).isNotNull()
                map.entries.forEach {
                    assertThat(loadAssignment.endpointsList)
                        .anySatisfy { x -> x.hasZoneWithPriority(it.key, it.value) }
                }
            }
    }

    private fun clusterState(
        locality: Locality = Locality.LOCAL,
        cluster: String,
        serviceName: String = this.serviceName
    ): ClusterState {
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

    private fun LocalityLbEndpoints.hasZoneWithPriority(zone: String, priority: Int) {
        assertThat(this.priority).isEqualTo(priority)
        assertThat(this.locality.zone).isEqualTo(zone)
    }

    private fun snapshotPropertiesWithPriorities(priorities: Map<String, Map<String, Int>>) =
        SnapshotProperties().apply {
            loadBalancing = LoadBalancingProperties()
                .apply {
                    this.priorities = LoadBalancingPriorityProperties().apply {
                        zonePriorities = priorities
                    }
                }
        }

    private fun String.toClusterLoadAssignment(): ClusterLoadAssignment = ClusterLoadAssignment.newBuilder()
        .also { builder -> JsonFormat.parser().merge(this, builder) }
        .build()
}
