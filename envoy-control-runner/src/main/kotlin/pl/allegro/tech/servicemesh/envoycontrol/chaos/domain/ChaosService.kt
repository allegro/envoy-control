package pl.allegro.tech.servicemesh.envoycontrol.chaos.domain

import pl.allegro.tech.servicemesh.envoycontrol.chaos.storage.ChaosDataStore
import pl.allegro.tech.servicemesh.envoycontrol.chaos.storage.NetworkDelay as NetworkDelayEntity

class ChaosService(val chaosRepository: ChaosDataStore) {

    fun submitNetworkDelay(
        networkDelay: NetworkDelay
    ): NetworkDelay = chaosRepository.save(networkDelay.toEntity()).toDomainObject()
}

data class NetworkDelay(
    val id: String,
    val source: String,
    val delay: String,
    val duration: String,
    val target: String
) {
    fun toEntity(): NetworkDelayEntity = NetworkDelayEntity(
        id = id,
        source = source,
        delay = delay,
        duration = duration,
        target = target
    )
}

fun NetworkDelayEntity.toDomainObject(): NetworkDelay = NetworkDelay(
    id = id,
    source = source,
    delay = delay,
    duration = duration,
    target = target
)
