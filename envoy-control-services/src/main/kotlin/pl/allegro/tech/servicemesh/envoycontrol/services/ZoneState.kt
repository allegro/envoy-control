package pl.allegro.tech.servicemesh.envoycontrol.services

enum class Locality {
    LOCAL, REMOTE
}

data class ZoneState(
    val servicesState: ServicesState,
    val locality: Locality,
    val zone: String
)

data class MultiZoneState(private val l: List<ZoneState> = listOf()) : Collection<ZoneState> by l {

    constructor(state: ZoneState) : this(listOf(state))

    companion object {
        fun empty() = MultiZoneState(emptyList())
        fun ZoneState.toMultiZoneState() = MultiZoneState(this)
        fun List<ZoneState>.toMultiZoneState() = MultiZoneState(this)
    }
}
