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

class ServiceNameBlacklistServiceWatchPolicy(
    private val blacklistedServiceNames: List<String>
) : ServiceWatchPolicy {
    override fun shouldBeWatched(service: String, tags: List<String>): Boolean =
        blacklistedServiceNames.contains(service).not()
}

class CombinedServiceWatchPolicy(
    private val firstPolicy: ServiceWatchPolicy,
    private val secondPolicy: ServiceWatchPolicy
) : ServiceWatchPolicy {
    override fun shouldBeWatched(service: String, tags: List<String>): Boolean =
        firstPolicy.shouldBeWatched(service, tags) && secondPolicy.shouldBeWatched(service, tags)
}

fun ServiceWatchPolicy.and(other: ServiceWatchPolicy): CombinedServiceWatchPolicy =
    CombinedServiceWatchPolicy(this, other)
