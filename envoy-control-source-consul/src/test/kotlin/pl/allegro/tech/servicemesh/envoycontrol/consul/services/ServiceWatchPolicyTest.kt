package pl.allegro.tech.servicemesh.envoycontrol.consul.services

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ServiceWatchPolicyTest {

    @Test
    fun `should return true when service tag is not present of tag blacklist`() {
        // given
        val tagsBlacklist = listOf("testTag")
        val serviceName = "envoy-control"
        val serviceTags = listOf("envoy", "application")
        val serviceWatchPolicy = TagBlacklistServiceWatchPolicy(tagsBlacklist)

        // when
        val shouldBeWatched = serviceWatchPolicy.shouldBeWatched(serviceName, serviceTags)

        // then
        assertThat(shouldBeWatched).isTrue
    }

    @Test
    fun `should return false when service tag is present of tag blacklist`() {
        // given
        val tagsBlacklist = listOf("envoy")
        val serviceName = "envoy-control"
        val serviceTags = listOf("envoy", "application")
        val serviceWatchPolicy = TagBlacklistServiceWatchPolicy(tagsBlacklist)

        // when
        val shouldBeWatched = serviceWatchPolicy.shouldBeWatched(serviceName, serviceTags)

        // then
        assertThat(shouldBeWatched).isFalse
    }

    @Test
    fun `should return true when service name is not present of name blacklist`() {
        // given
        val serviceNameBlacklist = listOf("consul")
        val serviceName = "envoy-control"
        val serviceTags = listOf("envoy", "application")
        val serviceWatchPolicy = ServiceNameBlacklistServiceWatchPolicy(serviceNameBlacklist)

        // when
        val shouldBeWatched = serviceWatchPolicy.shouldBeWatched(serviceName, serviceTags)

        // then
        assertThat(shouldBeWatched).isTrue
    }

    @Test
    fun `should return false when service name is present of name blacklist`() {
        // given
        val serviceNameBlacklist = listOf("envoy-control")
        val serviceName = "envoy-control"
        val serviceTags = listOf("envoy", "application")
        val serviceWatchPolicy = ServiceNameBlacklistServiceWatchPolicy(serviceNameBlacklist)

        // when
        val shouldBeWatched = serviceWatchPolicy.shouldBeWatched(serviceName, serviceTags)

        // then
        assertThat(shouldBeWatched).isFalse
    }

    @Test
    fun `should return true when all combined policies returns true`() {
        // given
        val serviceNameBlacklist = listOf("consul")
        val serviceTagsBlacklist = listOf("mongo")
        val serviceName = "envoy-control"
        val serviceTags = listOf("envoy", "application")
        val serviceWatchPolicy = ServiceNameBlacklistServiceWatchPolicy(serviceNameBlacklist)
            .and(TagBlacklistServiceWatchPolicy(serviceTagsBlacklist))

        // when
        val shouldBeWatched = serviceWatchPolicy.shouldBeWatched(serviceName, serviceTags)

        // then
        assertThat(shouldBeWatched).isTrue
    }

    @Test
    fun `should return false when any combined policies returns false`() {
        // given
        val serviceNameBlacklist = listOf("consul")
        val serviceTagsBlacklist = listOf("envoy")
        val serviceName = "envoy-control"
        val serviceTags = listOf("envoy", "application")
        val serviceWatchPolicy = ServiceNameBlacklistServiceWatchPolicy(serviceNameBlacklist)
            .and(TagBlacklistServiceWatchPolicy(serviceTagsBlacklist))

        // when
        val shouldBeWatched = serviceWatchPolicy.shouldBeWatched(serviceName, serviceTags)

        // then
        assertThat(shouldBeWatched).isFalse
    }
}
