package pl.allegro.tech.servicemesh.envoycontrol.consul.services

interface ServiceWatchPolicy {
    fun shouldBeWatched(service: String, tags: List<String>): Boolean
}

object NoOpServiceWatchPolicy : ServiceWatchPolicy {
    override fun shouldBeWatched(service: String, tags: List<String>): Boolean = true
}

class TagBlacklistServiceWatchPolicy(
    private val blacklistedTags: List<String>
) : ServiceWatchPolicy {
    override fun shouldBeWatched(service: String, tags: List<String>): Boolean =
        blacklistedTags.any { tags.contains(it) }.not()
}
