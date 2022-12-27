package pl.allegro.tech.servicemesh.envoycontrol.snapshot

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.services.ClusterState
import pl.allegro.tech.servicemesh.envoycontrol.services.Locality
import pl.allegro.tech.servicemesh.envoycontrol.services.MultiClusterState
import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceInstance
import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceInstances
import pl.allegro.tech.servicemesh.envoycontrol.services.ServicesState
import java.util.concurrent.ConcurrentHashMap

class EnvoySnapshotFactoryTest {

    @Test
    fun `should return all tags when prefix is empty`() {
        // given
        val tagPrefix = ""
        val serviceTags = mapOf(
            serviceWithTags("abc", "uws", "poc"),
            serviceWithTags("xyz", "uj"),
            serviceWithTags("qwerty")
        )
        val state = MultiClusterState(listOf(
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
        val serviceTags = mapOf(
            serviceWithTags("abc", "tag:uws", "poc"),
            serviceWithTags("xyz", "uj"),
            serviceWithTags("qwerty")
        )
        val state = MultiClusterState(listOf(
            ClusterState(serviceState(serviceTags), Locality.LOCAL, "cluster")
        ))

        // when
        val tags = EnvoySnapshotFactory.tagExtractor(tagPrefix, state)

        // then
        assertThat(tags).isEqualTo(mapOf(
            serviceWithTags("abc", "tag:uws"),
            serviceWithTags("xyz"),
            serviceWithTags("qwerty")
        ))
    }

    @Test
    fun `should merge multiple Cluster State`() {
        // given
        val tagPrefix = ""
        val serviceTagsCluster1 = mapOf(
            serviceWithTags("abc", "uws", "poc"),
            serviceWithTags("xyz", "uj"),
            serviceWithTags("qwerty"))
        val serviceTagsCluster2 = mapOf(
            serviceWithTags("abc", "lkj"),
            serviceWithTags("xyz"),
            serviceWithTags("qwerty", "ban"))
        val state = MultiClusterState(listOf(
            ClusterState(serviceState(serviceTagsCluster1), Locality.LOCAL, "cluster"),
            ClusterState(serviceState(serviceTagsCluster2), Locality.LOCAL, "cluster2")
        ))

        // when
        val tags = EnvoySnapshotFactory.tagExtractor(tagPrefix, state)

        // then
        assertThat(tags).isEqualTo(mapOf(
            serviceWithTags("abc", "uws", "poc", "lkj"),
            serviceWithTags("xyz", "uj"),
            serviceWithTags("qwerty", "ban")
        ))
    }

    private fun serviceState(servicesTags: Map<String, Set<String>>): ServicesState {
        val servicesInstances = servicesTags.map {
            it.key to setOf(ServiceInstance("${it.key}-1", it.value, null, null))
        }.associateTo(ConcurrentHashMap()) { it.first to ServiceInstances(it.first, it.second) }
        return ServicesState(servicesInstances)
    }
}

private fun serviceWithTags(serviceName: String, vararg tags: String): Pair<String, Set<String>> {
    return serviceName to tags.toSet()
}
