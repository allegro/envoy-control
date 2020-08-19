package pl.allegro.tech.servicemesh.envoycontrol.chaos.storage

class ChaosDataStoreService : ChaosDataStore {
    override fun save(item: NetworkDelay): NetworkDelay = item
}
