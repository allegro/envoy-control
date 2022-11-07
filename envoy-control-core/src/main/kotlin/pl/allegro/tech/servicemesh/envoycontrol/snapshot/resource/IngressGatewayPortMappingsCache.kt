package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource

import pl.allegro.tech.servicemesh.envoycontrol.groups.AllServicesIngressGatewayGroup
import pl.allegro.tech.servicemesh.envoycontrol.groups.IngressGatewayGroup
import pl.allegro.tech.servicemesh.envoycontrol.groups.ServicesIngressGatewayGroup
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

typealias GatewayName = String
typealias Port = Int
typealias Cluster = String

class IngressGatewayPortMappingsCache {
    private val serviceIngressGatewaysMappings: ConcurrentMap<GatewayName, Map<Cluster, Port>> = ConcurrentHashMap()
    private val allServicesIngressGatewayMappings: ConcurrentMap<GatewayName, Map<Cluster, Port>> = ConcurrentHashMap()

    fun addMapping(group: IngressGatewayGroup, mapping: Map<Cluster, Port>) {
        when (group) {
            is AllServicesIngressGatewayGroup -> allServicesIngressGatewayMappings[group.discoveryServiceName] = mapping
            is ServicesIngressGatewayGroup -> serviceIngressGatewaysMappings[group.discoveryServiceName] = mapping
        }
    }
    fun ingressGatewayMapping(name: GatewayName): Map<Cluster, Port> = serviceIngressGatewaysMappings[name] ?: mapOf()
    fun dcIngressGatewayMapping(name: GatewayName): Map<Cluster, Port> = serviceIngressGatewaysMappings[name] ?: mapOf()
    fun dcIngressGatewayNames() = allServicesIngressGatewayMappings.keys
}
