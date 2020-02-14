package pl.allegro.tech.servicemesh.envoycontrol.services.transformers

import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceInstance
import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceInstances

class InstanceMerger : ServiceInstancesTransformer {

    override fun transform(services: Sequence<ServiceInstances>): Sequence<ServiceInstances> = services.map {

        val containsDuplicates = it.instances
            .groupingBy { it.address to it.port }.eachCount()
            .any { it.value > 1 }

        if (containsDuplicates) {
            it.copy(instances = merge(it.instances))
        }
        else {
            it
        }
    }

    private fun merge(instances: Set<ServiceInstance>): Set<ServiceInstance> = instances
        .groupBy { it.address to it.port }
        .map { (target, instances) ->
            if (instances.size == 1) {
                instances[0]
            }
            else {
                ServiceInstance(
                    id = instances.map { it.id }.joinToString(","),
                    tags = instances.map { it.tags }.reduce { s1, s2 -> s1 + s2 },
                    address = target.first,
                    port = target.second,
                    regular = instances.any { it.regular },
                    canary = instances.any { it.canary },
                    weight = instances.sumBy { it.weight }
                )
            }
        }
        .toSet()
}
