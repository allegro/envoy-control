package pl.allegro.tech.servicemesh.envoycontrol.config.envoy

import pl.allegro.tech.servicemesh.envoycontrol.config.service.UpstreamService

class CallStats(private val upstreamServices: List<UpstreamService>) {
    var failedHits: Int = 0
    var totalHits: Int = 0

    private var containerHits: MutableMap<String, Int> =
        upstreamServices.associate { it.id() to 0 }.toMutableMap()

    fun hits(upstreamService: UpstreamService) = containerHits[upstreamService.id()] ?: 0

    fun addResponse(response: ResponseWithBody) {
        upstreamServices
            .firstOrNull { it.isSourceOf(response) }
            .let { it ?: throw AssertionError("response from unknown instance") }
            .let { containerHits.compute(it.id()) { _, i -> i?.inc() } }
        if (!response.isOk()) failedHits++
        totalHits++
    }
}
