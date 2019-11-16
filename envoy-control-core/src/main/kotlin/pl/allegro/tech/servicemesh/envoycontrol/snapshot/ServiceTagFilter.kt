package pl.allegro.tech.servicemesh.envoycontrol.snapshot

interface ServiceTagFilter {
    fun filterTagsForRouting(tags: Set<String>): Set<String>
    fun isAllowedToMatchOnTwoTags(serviceName: String): Boolean
    fun isAllowedToMatchOnThreeTags(serviceName: String): Boolean
    fun canBeCombined(tag1: String, tag2: String): Boolean
}

open class DefaultServiceTagFilter(
    private val properties: ServiceTagsProperties = ServiceTagsProperties()
) : ServiceTagFilter {

    private val tagsBlacklist: List<Regex> = properties.routingExcludedTags.map { Regex(it) }

    override fun filterTagsForRouting(tags: Set<String>): Set<String> = tags
        .filter { tag -> tagsBlacklist.none { tag.matches(it) } }
        .toSet()

    override fun isAllowedToMatchOnTwoTags(serviceName: String): Boolean = properties.twoTagsRoutingAllowedServices
        .contains(serviceName)

    override fun isAllowedToMatchOnThreeTags(serviceName: String): Boolean = properties.threeTagsRoutingAllowedServices
        .contains(serviceName)

    override fun canBeCombined(tag1: String, tag2: String): Boolean = true
}
