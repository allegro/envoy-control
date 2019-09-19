package pl.allegro.tech.servicemesh.envoycontrol.services

data class LocalityAwareServicesState(
    val servicesState: ServicesState,
    val locality: Locality,
    val zone: String
)

enum class Locality {
    LOCAL, REMOTE
}
