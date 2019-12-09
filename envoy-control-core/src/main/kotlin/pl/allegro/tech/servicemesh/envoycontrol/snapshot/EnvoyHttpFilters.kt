package pl.allegro.tech.servicemesh.envoycontrol.snapshot

class EnvoyHttpFilters(
    val ingressFilters: List<HttpFilterFactory>,
    val egressFilters: List<HttpFilterFactory>
) {
    companion object {
        val emptyFilters = EnvoyHttpFilters(listOf(), listOf())
        val defaultEgressFilters = EnvoyListenersFactory.defaultEgressFilters
        val defaultIngressFilters = EnvoyListenersFactory.defaultIngressFilters
    }
}
