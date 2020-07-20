package pl.allegro.tech.servicemesh.envoycontrol.config.envoy

import pl.allegro.tech.servicemesh.envoycontrol.config.containers.EchoContainer

class CallStats(private val containers: List<EchoContainer>) {
    var failedHits: Int = 0
    var totalHits: Int = 0

    private var containerHits: MutableMap<String, Int> = containers.associate { it.containerId to 0 }.toMutableMap()

    fun hits(container: EchoContainer) = containerHits[container.containerId] ?: 0

    fun addResponse(response: ResponseWithBody) {
        containers.firstOrNull { response.isFrom(it) }
            ?.let { containerHits.compute(it.containerId) { _, i -> i?.inc() } }
        if (!response.isOk()) failedHits++
        totalHits++
    }
}
