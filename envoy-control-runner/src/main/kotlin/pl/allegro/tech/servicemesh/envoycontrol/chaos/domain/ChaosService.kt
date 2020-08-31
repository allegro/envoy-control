package pl.allegro.tech.servicemesh.envoycontrol.chaos.domain

import pl.allegro.tech.servicemesh.envoycontrol.chaos.storage.ChaosDataStore
import pl.allegro.tech.servicemesh.envoycontrol.chaos.storage.NetworkDelay as NetworkDelayEntity

class ChaosService(val chaosDataStore: ChaosDataStore) {

    fun submitNetworkDelay(
        networkDelay: NetworkDelay
    ): NetworkDelay = chaosDataStore.save(item = networkDelay.toEntity()).toDomainObject()

    fun getExperimentsList(): List<NetworkDelay> = chaosDataStore.get().map { it.toDomainObject() }

    fun deleteNetworkDelay(
        networkDelayId: String
    ) {
        chaosDataStore.delete(id = networkDelayId)
    }
}

data class NetworkDelay(
    val id: String,
    val affectedService: String,
    val delay: String,
    val duration: String,
    val targetService: String
) {
    fun toEntity(): NetworkDelayEntity = NetworkDelayEntity(
        id = id,
        affectedService = affectedService,
        delay = delay,
        duration = duration,
        targetService = targetService
    )
}

fun NetworkDelayEntity.toDomainObject(): NetworkDelay = NetworkDelay(
    id = id,
    affectedService = affectedService,
    delay = delay,
    duration = duration,
    targetService = targetService
)
