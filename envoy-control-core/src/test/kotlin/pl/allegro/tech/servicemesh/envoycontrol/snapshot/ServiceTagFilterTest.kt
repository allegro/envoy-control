package pl.allegro.tech.servicemesh.envoycontrol.snapshot

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class ServiceTagFilterTest {

    private val filter = ServiceTagFilter(ServiceTagsProperties().apply {
        enabled = true
        routingExcludedTags = mutableListOf(".*id.*", "port:.*")
        allowedTagsCombinations = mutableListOf(
            ServiceTagsCombinationsProperties().apply {
                serviceName = "two-tags-allowed-service"
                tags = mutableListOf("hardware:.*", "version:.*")
            },
            ServiceTagsCombinationsProperties().apply {
                serviceName = "two-tags-allowed-service"
                tags = mutableListOf("stage:.*", "version:.*")
            },
            ServiceTagsCombinationsProperties().apply {
                serviceName = "three-tags-allowed-service"
                tags = mutableListOf("stage:.*", "version:.*", "hardware:.*")
            }
        )
    })

    @Test
    fun `should return filtered tags without combinations`() {
        // when
        val routingTags = filter.getAllTagsForRouting(
            "regular-service",
            setOf("id:332", "hardware:c32", "stage:dev", "version:v0.9", "env12", "port:1244", "gport:1245")
        )
        // then
        assertThat(routingTags).isNotNull
        assertThat(routingTags!!.toSet()).isEqualTo(setOf(
            "hardware:c32",
            "stage:dev",
            "version:v0.9",
            "env12",
            "gport:1245"
        ))
    }

    @Test
    fun `should generate two tags combinations`() {

        // when
        val routingTags = filter.getAllTagsForRouting(
            "two-tags-allowed-service",
            setOf("service-id:332", "hardware:c32", "stage:dev", "version:v0.9", "env12")
        )

        // then
        assertThat(routingTags).isNotNull
        assertThat(routingTags!!.toSet()).isEqualTo(setOf(
            "hardware:c32",
            "stage:dev",
            "version:v0.9",
            "env12",
            "hardware:c32,version:v0.9",
            "stage:dev,version:v0.9"
        ))
    }

    @Test
    fun `should generate three tags combinations`() {

        // when
        val routingTags = filter.getAllTagsForRouting(
            "three-tags-allowed-service",
            setOf("service-id:332", "hardware:c32", "stage:dev", "version:v0.9", "port:3200")
        )

        // then
        assertThat(routingTags).isNotNull
        assertThat(routingTags!!.toSet()).isEqualTo(setOf(
            "hardware:c32",
            "stage:dev",
            "version:v0.9",
            "hardware:c32,stage:dev",
            "hardware:c32,version:v0.9",
            "stage:dev,version:v0.9",
            "hardware:c32,stage:dev,version:v0.9"
        ))
    }

    @Test
    fun `should return null for service with no valid tags`() {

        // when
        val routingTags = filter.getAllTagsForRouting(
            "three-tags-allowed-service",
            setOf("port:12344", "sid:3333")
        )

        // then
        assertThat(routingTags).isNull()
    }
}
