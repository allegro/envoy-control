package pl.allegro.tech.servicemesh.envoycontrol.chaos.storage

interface ChaosDataStore {
    fun save(item: NetworkDelay): NetworkDelay
    fun get(): List<NetworkDelay>
    fun delete(id: String)
}

data class NetworkDelay(
    val id: String,
    val affectedService: String,
    val delay: String,
    val duration: String,
    val targetService: String
)
