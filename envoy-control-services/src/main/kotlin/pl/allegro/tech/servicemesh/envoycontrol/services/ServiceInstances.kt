package pl.allegro.tech.servicemesh.envoycontrol.services

data class ServiceInstance(
    val id: String,
    val tags: Set<String>,
    val address: String,
    val port: Int,
    val regular: Boolean = true,
    val canary: Boolean = false,
    val weight: Int = 1
)

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

    fun sorted() = copy(instances = instances
        .map { serviceInstance -> serviceInstance.copy(tags = serviceInstance.tags.toSortedSet()) }
        .toSortedSet(compareBy { it.id }))
}
