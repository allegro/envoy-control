package pl.allegro.tech.servicemesh.envoycontrol.services

data class ServiceInstances(
    val serviceName: String,
    val instances: Set<ServiceInstance>
) {
    fun withoutEmptyAddressInstances(): ServiceInstances =
        if (instances.any { it.address.isBlank() }) {
            copy(instances = instances.asSequence()
                .filter { it.address.isNotBlank() }
                .toSet())
        } else this
}
