package pl.allegro.tech.servicemesh.envoycontrol.synchronization

import pl.allegro.tech.servicemesh.envoycontrol.services.LocalClusterStateChanges
import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceInstance
import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceInstances
import pl.allegro.tech.servicemesh.envoycontrol.services.ServicesState
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.IngressGatewayPortMappingsCache
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.Port
import java.util.concurrent.ConcurrentHashMap

class SyncableLocalServiceStateCreator(
    private val localClusterStateChanges: LocalClusterStateChanges,
    private val mappingsCache: IngressGatewayPortMappingsCache
) {

    fun createSyncableLocalState(): ServicesState {
        val localState = localClusterStateChanges.latestServiceState.get()
        val dcIngressGatewaysNames = mappingsCache.dcIngressGatewayNames()
        val (dcIngressGateways, services) = localState.allInstances()
            .partition { dcIngressGatewaysNames.contains(it.serviceName) }
        val servicesMap = services.map {
            val instances = dcIngressGateways.mapNotNull { gatewayServiceInstances ->
                val port = mappingsCache.dcIngressGatewayMapping(gatewayServiceInstances.serviceName)[it.serviceName]
                if (port != null) {
                    mergeDcIngressGatewayWithServiceInstances(gatewayServiceInstances.instances, it.instances, port)
                } else {
                    null
                }
            }.flatten().toSet().ifEmpty { it.instances }
            ServiceInstances(serviceName = it.serviceName, instances = instances)
        }.associateBy { it.serviceName }

        return ServicesState(ConcurrentHashMap(servicesMap))
    }

    private fun mergeDcIngressGatewayWithServiceInstances(
        ingressGatewayInstances: Set<ServiceInstance>,
        serviceInstances: Set<ServiceInstance>,
        port: Port
    ): Set<ServiceInstance> {
        var isCanary = false
        var isReqular = false
        val tags = mutableSetOf<String>()
        var maxWeight = 0
        for (instance in serviceInstances) {
            tags.addAll(instance.tags)
            if (!isCanary) {
                isCanary = instance.canary
            }
            if (!isReqular) {
                isReqular = instance.regular
            }
            if (instance.weight > maxWeight) {
                maxWeight = instance.weight
            }
        }
        return ingressGatewayInstances.map {
            ServiceInstance(
                id = it.id,
                tags = tags,
                address = it.address,
                port = port,
                regular = isReqular,
                canary = isCanary,
                weight = maxWeight
            )
        }.toSet()
    }
}
