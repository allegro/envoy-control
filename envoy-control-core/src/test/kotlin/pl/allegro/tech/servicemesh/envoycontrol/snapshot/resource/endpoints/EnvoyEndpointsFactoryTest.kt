package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.endpoints

import com.google.protobuf.util.JsonFormat
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.groups.RoutingPolicy
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotProperties

internal class EnvoyEndpointsFactoryTest {

    private val endpointsFactory = EnvoyEndpointsFactory(SnapshotProperties())

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
            },
            {
              "locality": { "zone": "south" },
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
              "locality": { "zone": "west" },
              "lb_endpoints": [] 
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

        // when
        val filtered = endpointsFactory.filterEndpoints(globalLoadAssignment, policy)

        // then
        assertThat(filtered)
            .isNotEqualTo(globalLoadAssignmentJson.toClusterLoadAssignment())
            .isEqualTo(expectedLoadAssignmentJson.toClusterLoadAssignment())

        val eastEndpoints = filtered.getEndpoints(1)
        val globalEastEndpoints = globalLoadAssignment.getEndpoints(1)
        assertThat(eastEndpoints).describedAs("unnecessary copy!").isSameAs(globalEastEndpoints)

        val southEndpoints = filtered.getEndpoints(1)
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

    private fun String.toClusterLoadAssignment(): ClusterLoadAssignment = ClusterLoadAssignment.newBuilder()
        .also { builder -> JsonFormat.parser().merge(this, builder) }
        .build()
}
