package pl.allegro.tech.servicemesh.envoycontrol.chaos.storage

class SimpleChaosDataStore : ChaosDataStore {

    private val dataStore: MutableMap<String, NetworkDelay> = mutableMapOf()

    override fun save(item: NetworkDelay): NetworkDelay = item.also { dataStore[item.id] = item }
    override fun get(): List<NetworkDelay> = dataStore.map { item -> item.value }
    override fun delete(id: String) {
        dataStore.remove(id)
    }
}
