package pl.allegro.tech.servicemesh.envoycontrol.services.transformers

import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceInstances

class EmptyAddressFilter : ServiceInstancesTransformer {

    override fun transform(services: Sequence<ServiceInstances>): Sequence<ServiceInstances> =
        services.map { serviceInstances -> serviceInstances.withoutEmptyAddressInstances() }
}
