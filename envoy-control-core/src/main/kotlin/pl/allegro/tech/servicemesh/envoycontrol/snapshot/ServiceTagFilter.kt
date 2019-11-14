package pl.allegro.tech.servicemesh.envoycontrol.snapshot

interface ServiceTagFilter {
    fun filterTagsForRouting(tags: Set<String>): Set<String>
}

class DefaultServiceTagFilter : ServiceTagFilter {
    override fun filterTagsForRouting(tags: Set<String>): Set<String> = tags
}
