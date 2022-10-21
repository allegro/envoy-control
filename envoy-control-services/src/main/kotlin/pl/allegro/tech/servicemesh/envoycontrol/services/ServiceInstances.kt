package pl.allegro.tech.servicemesh.envoycontrol.services

data class ServiceInstance(
    val id: String,
    val tags: Set<String>,
    val address: String?,
    val port: Int?,
    val regular: Boolean = true,
    val canary: Boolean = false,
    val weight: Int = 1
)

data class ServiceInstances(
    val serviceName: String,
    val instances: Set<ServiceInstance>
) {
    fun withoutEmptyAddressInstances(): ServiceInstances =
        if (instances.any { it.address.isNullOrEmpty() }) {
            copy(instances = instances.asSequence()
                .filter { !it.address.isNullOrEmpty() }
                .toSet())
        } else this

    fun withoutInvalidPortInstances(): ServiceInstances =
        if (instances.any { it.port == null }) {
            copy(instances = instances.asSequence()
                .filter { it.port != null }
                .toSet())
        } else this
}
