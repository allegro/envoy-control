package pl.allegro.tech.servicemesh.envoycontrol.services

typealias ServiceName = String

data class ServicesState(
    val serviceNameToInstances: MutableMap<ServiceName, ServiceInstances> = mutableMapOf()
) {
    operator fun get(serviceName: ServiceName): ServiceInstances? = serviceNameToInstances[serviceName]

    fun hasService(serviceName: String): Boolean = serviceNameToInstances.containsKey(serviceName)
    fun serviceNames(): Set<ServiceName> = serviceNameToInstances.keys
    fun allInstances(): Collection<ServiceInstances> = serviceNameToInstances.values

    fun remove(serviceName: ServiceName): Boolean {
        return serviceNameToInstances.remove(serviceName) != null
    }

    fun add(serviceName: ServiceName): Boolean {
        return if (serviceNameToInstances.containsKey(serviceName)) {
            false
        } else {
            change(ServiceInstances(serviceName, instances = emptySet()))
        }
    }

    fun change(serviceInstances: ServiceInstances): Boolean {
        return if (serviceNameToInstances[serviceInstances.serviceName] == serviceInstances) {
            false
        } else {
            serviceNameToInstances[serviceInstances.serviceName] = serviceInstances
            true
        }
    }
}
