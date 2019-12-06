package pl.allegro.tech.servicemesh.envoycontrol.snapshot

import io.envoyproxy.envoy.config.filter.network.http_connection_manager.v2.HttpFilter

class EnvoyHttpFilters(
    val ingressFilters: List<HttpFilter>,
    val egressFilters: List<HttpFilter>
) {
    companion object {
        val emptyFilters = EnvoyHttpFilters(listOf(), listOf())
        val defaultEgressFilters = EnvoyListenersFactory.defaultEgressFilters
        val defaultIngressFilters = EnvoyListenersFactory.defaultIngressFilters
    }
}
