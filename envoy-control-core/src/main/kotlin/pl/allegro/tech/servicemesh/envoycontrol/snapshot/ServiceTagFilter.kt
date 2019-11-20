package pl.allegro.tech.servicemesh.envoycontrol.snapshot

class ServiceTagFilter(properties: ServiceTagsProperties = ServiceTagsProperties()) {

    private val tagsBlacklist: List<Regex> = properties.routingExcludedTags.map { Regex(it) }
    private val twoTagsCombinationsByService: Map<String, List<Pair<Regex, Regex>>>
    private val threeTagsCombinationsByService: Map<String, List<Triple<Regex, Regex, Regex>>>

    init {
        properties.allowedTagsCombinations.forEach {
            if (it.tags.size < 2 || it.tags.size > 3) {
                throw IllegalArgumentException(
                    "A tags combination must contain 2 or 3 tags. Combination with ${it.tags.size} tags found")
            }
        }
        val combinationsByService = properties.allowedTagsCombinations
            .groupBy({ it.serviceName }) { it.tags }

        threeTagsCombinationsByService = combinationsByService
            .mapValues { it.value.filter { it.size == 3 } }
            .mapValues { it.value
                .map { it.sorted().map { Regex(it) } }
                .map { Triple(it[0], it[1], it[2]) }
                .distinct()
            }
            .filterValues { it.isNotEmpty() }

        twoTagsCombinationsByService = combinationsByService
            .mapValues { it.value.filter { it.size == 2 } }
            .mapValues { it.value
                .map { it.sorted().map { Regex(it) } }
                .map { it[0] to it[1] }
                .distinct()
            }
            .mapValues { (serviceName, combinations) ->
                combinations + threeTagsCombinationsByService[serviceName].orEmpty()
                    .flatMap { it.getAllPairs() }
            }
            .filterValues { it.isNotEmpty() }
    }

    fun filterTagsForRouting(tags: Set<String>): Set<String> = tags
        .filter { tag -> tagsBlacklist.none { tag.matches(it) } }
        .toSet()

    fun isAllowedToMatchOnTwoTags(serviceName: String): Boolean = twoTagsCombinationsByService.contains(serviceName)

    fun isAllowedToMatchOnThreeTags(serviceName: String): Boolean = threeTagsCombinationsByService.contains(serviceName)

    /**
     * Assuming tags are sorted lexicographically, that is: tag1 < tag2
     */
    fun canBeCombined(serviceName: String, tag1: String, tag2: String): Boolean {
        return twoTagsCombinationsByService[serviceName].orEmpty()
            .any { (pattern1, pattern2) ->
                tag1.matches(pattern1) && tag2.matches(pattern2)
            }
    }

    /**
     * Assuming tags are sorted lexicographically, that is: tag1 < tag2 < tag3
     */
    fun canBeCombined(serviceName: String, tag1: String, tag2: String, tag3: String): Boolean {
        return threeTagsCombinationsByService[serviceName].orEmpty()
            .any { (pattern1, pattern2, pattern3) ->
                tag1.matches(pattern1) && tag2.matches(pattern2) && tag3.matches(pattern3)
            }
    }

    private fun <T> Triple<T, T, T>.getAllPairs(): List<Pair<T, T>> {
        return listOf(
            this.first to this.second,
            this.first to this.third,
            this.second to this.third
        )
    }
}
