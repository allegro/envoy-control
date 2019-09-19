package pl.allegro.tech.servicemesh.envoycontrol.services.transformers

import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceInstances

/**
 * TODO GITHUB-ISSUE Envoy & Envoy Control supports only IP and not hostnames
 */
class IpAddressFilter : ServiceInstancesTransformer {

    override fun transform(services: Sequence<ServiceInstances>): Sequence<ServiceInstances> =
        services.filter { (_, instances) ->
            instances.all { isIpAddress(it.address) }
        }

    private fun isIpAddress(address: String): Boolean = address.all { !it.isLetter() }
}
