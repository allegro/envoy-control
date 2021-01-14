package pl.allegro.tech.servicemesh.envoycontrol.config.envoy

import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension

class CallStats(private val serviceExtensions: List<EchoServiceExtension>) {
    var failedHits: Int = 0
    var totalHits: Int = 0

    private var containerHits: MutableMap<String, Int> =
        serviceExtensions.associate { it.container().containerId to 0 }.toMutableMap()

    fun hits(extension: EchoServiceExtension) = containerHits[extension.container().containerId] ?: 0

    fun addResponse(response: ResponseWithBody) {
        serviceExtensions
            .map { it.container() }
            .firstOrNull { response.isFrom(it) }
            ?.let { containerHits.compute(it.containerId) { _, i -> i?.inc() } }
        if (!response.isOk()) failedHits++
        totalHits++
    }
}
