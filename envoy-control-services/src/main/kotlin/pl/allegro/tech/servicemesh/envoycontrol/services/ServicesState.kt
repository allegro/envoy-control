package pl.allegro.tech.servicemesh.envoycontrol.services

typealias ServiceName = String

data class ServicesState(
    val serviceNameToInstances: Map<ServiceName, ServiceInstances> = emptyMap()
) {
    operator fun get(serviceName: ServiceName): ServiceInstances? = serviceNameToInstances[serviceName]

    fun hasService(serviceName: String): Boolean = serviceNameToInstances.containsKey(serviceName)
    fun serviceNames(): Set<ServiceName> = serviceNameToInstances.keys
    fun allInstances(): Collection<ServiceInstances> = serviceNameToInstances.values

    fun remove(serviceName: ServiceName): ServicesState {
        // TODO: https://github.com/allegro/envoy-control/issues/11
        return change(ServiceInstances(serviceName, instances = emptySet()))
    }

    fun add(serviceName: ServiceName): ServicesState =
        if (serviceNameToInstances.containsKey(serviceName)) {
            this
        } else {
            change(ServiceInstances(serviceName, instances = emptySet()))
        }

    fun change(serviceInstances: ServiceInstances): ServicesState =
        if (serviceNameToInstances[serviceInstances.serviceName]?.sorted() == serviceInstances) {
            this
        } else {
            copy(serviceNameToInstances = serviceNameToInstances + (serviceInstances.serviceName to serviceInstances))
        }
}
