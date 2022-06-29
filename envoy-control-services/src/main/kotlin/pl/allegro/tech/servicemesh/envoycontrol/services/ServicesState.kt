package pl.allegro.tech.servicemesh.envoycontrol.services

import java.util.concurrent.ConcurrentHashMap

typealias ServiceName = String

data class ServicesState(
    // TODO this field should be private but right now jackson ignores it and it cannot be instantiate.
    //  Will fix this i next pr
    val serviceNameToInstances: ConcurrentHashMap<ServiceName, ServiceInstances> = ConcurrentHashMap()
) {
    operator fun get(serviceName: ServiceName): ServiceInstances? = serviceNameToInstances[serviceName]

    fun hasService(serviceName: String): Boolean = serviceNameToInstances.containsKey(serviceName)
    fun serviceNames(): Set<ServiceName> = serviceNameToInstances.keys
    fun allInstances(): Collection<ServiceInstances> = serviceNameToInstances.values

    fun removeServicesWithoutInstances(): ServicesState {
        serviceNameToInstances.entries.retainAll { (_, value) -> value.instances.isNotEmpty() }
        return this
    }

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
