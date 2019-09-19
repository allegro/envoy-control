package pl.allegro.tech.servicemesh.envoycontrol.services.transformers

import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceInstances

interface ServiceInstancesTransformer {
    fun transform(services: Sequence<ServiceInstances>): Sequence<ServiceInstances>
}
