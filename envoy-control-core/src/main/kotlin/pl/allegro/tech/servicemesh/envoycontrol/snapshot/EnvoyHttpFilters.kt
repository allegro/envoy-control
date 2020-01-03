package pl.allegro.tech.servicemesh.envoycontrol.snapshot

class EnvoyHttpFilters(
    val ingressFilters: List<HttpFilterFactory>,
    val egressFilters: List<HttpFilterFactory>
) {
    companion object {
        val emptyFilters = EnvoyHttpFilters(listOf(), listOf())

        fun defaultFilters(snapshotProperties: SnapshotProperties): EnvoyHttpFilters {
            val defaultFilters = EnvoyDefaultFilters(snapshotProperties)
            return EnvoyHttpFilters(defaultFilters.defaultIngressFilters, defaultFilters.defaultEgressFilters)
        }
    }
}
