package pl.allegro.tech.servicemesh.envoycontrol.services

enum class Locality {
    LOCAL, REMOTE
}

data class ClusterState(
    val servicesState: ServicesState,
    val locality: Locality,
    val zone: String
)

class MultiClusterState(l: List<ClusterState> = listOf()) : Collection<ClusterState> by l {

    constructor(state: ClusterState) : this(listOf(state))

    companion object {
        fun empty() = MultiClusterState(emptyList())
        fun ClusterState.toMultiClusterState() = MultiClusterState(this)
        fun List<ClusterState>.toMultiClusterState() = MultiClusterState(this)
    }
}
