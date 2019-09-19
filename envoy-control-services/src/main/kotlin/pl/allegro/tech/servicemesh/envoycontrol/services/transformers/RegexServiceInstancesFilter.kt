package pl.allegro.tech.servicemesh.envoycontrol.services.transformers

import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceInstances

class RegexServiceInstancesFilter(private val excludedRegexes: Collection<Regex>) : ServiceInstancesTransformer {

    override fun transform(services: Sequence<ServiceInstances>): Sequence<ServiceInstances> =
        services.filter { (serviceName, _) ->
            !excludedRegexes.any { serviceName.matches(it) }
        }
}
