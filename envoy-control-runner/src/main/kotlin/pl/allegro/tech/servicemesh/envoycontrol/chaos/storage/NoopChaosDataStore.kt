package pl.allegro.tech.servicemesh.envoycontrol.chaos.storage

class NoopChaosDataStore : ChaosDataStore {
    override fun save(item: NetworkDelay): NetworkDelay = item
}
