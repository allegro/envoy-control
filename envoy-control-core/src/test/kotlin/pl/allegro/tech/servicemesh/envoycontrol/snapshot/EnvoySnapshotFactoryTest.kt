package pl.allegro.tech.servicemesh.envoycontrol.snapshot

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.services.ClusterState
import pl.allegro.tech.servicemesh.envoycontrol.services.Locality
import pl.allegro.tech.servicemesh.envoycontrol.services.MultiClusterState
import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceInstance
import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceInstances
import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceName
import pl.allegro.tech.servicemesh.envoycontrol.services.ServicesState
import java.util.concurrent.ConcurrentHashMap

class EnvoySnapshotFactoryTest {

    @Test
    fun `should return all tags when prefix is empty`() {
        // given
        val tagPrefix = ""
        val serviceTags = mapOf("abc" to setOf("uws", "poc"), "xyz" to setOf("uj"), "qwerty" to setOf())
        val state: MultiClusterState = MultiClusterState(listOf(
            ClusterState(serviceState(serviceTags), Locality.LOCAL, "cluster")
        ))

        // when
        val tags = EnvoySnapshotFactory.tagExtractor(tagPrefix, state)

        // then
        assertThat(tags).isEqualTo(serviceTags)
    }

    @Test
    fun `should return all tags with prefix`() {
        val tagPrefix = "tag:"
        val serviceTags = mapOf("abc" to setOf("tag:uws", "poc"), "xyz" to setOf("uj"), "qwerty" to setOf())
        val state: MultiClusterState = MultiClusterState(listOf(
            ClusterState(serviceState(serviceTags), Locality.LOCAL, "cluster")
        ))

        // when
        val tags = EnvoySnapshotFactory.tagExtractor(tagPrefix, state)

        // then
        assertThat(tags).isEqualTo(mapOf(
            "abc" to setOf("tag:uws"),
            "xyz" to emptySet(),
            "qwerty" to emptySet()
        ))
    }

    @Test
    fun `should merge multiple Cluster State`() {
        // given
        val tagPrefix = ""
        val serviceTagsCluster1 = mapOf("abc" to setOf("uws", "poc"), "xyz" to setOf("uj"), "qwerty" to setOf())
        val serviceTagsCluster2 = mapOf("abc" to setOf("lkj"), "xyz" to setOf(), "qwerty" to setOf("ban"))
        val state: MultiClusterState = MultiClusterState(listOf(
            ClusterState(serviceState(serviceTagsCluster1), Locality.LOCAL, "cluster"),
            ClusterState(serviceState(serviceTagsCluster2), Locality.LOCAL, "cluster2")
        ))

        // when
        val tags = EnvoySnapshotFactory.tagExtractor(tagPrefix, state)

        // then
        assertThat(tags).isEqualTo(mapOf(
            "abc" to setOf("uws", "poc", "lkj"),
            "xyz" to setOf("uj"),
            "qwerty" to setOf("ban")
        ))
    }

    private fun serviceState(servicesTags: Map<String, Set<String>>): ServicesState {
        val map: ConcurrentHashMap<ServiceName, ServiceInstances> = ConcurrentHashMap()
        servicesTags.forEach {
            val instances = listOf(1, 2, 3).map { id ->
                ServiceInstance("${it.key}-$id", it.value, null, null)
            }.toSet()
            map[it.key] = ServiceInstances(it.key, instances)
        }
        return ServicesState(map)
    }
}
