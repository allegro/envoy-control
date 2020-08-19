package pl.allegro.tech.servicemesh.envoycontrol.chaos.storage

interface ChaosDataStore {
    fun save(item: NetworkDelay): NetworkDelay
}

data class NetworkDelay(
    val id: String,
    val source: String,
    val delay: String,
    val duration: String,
    val target: String
)
