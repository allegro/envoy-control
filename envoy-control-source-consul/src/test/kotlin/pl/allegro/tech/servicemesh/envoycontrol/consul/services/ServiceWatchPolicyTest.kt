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
}
