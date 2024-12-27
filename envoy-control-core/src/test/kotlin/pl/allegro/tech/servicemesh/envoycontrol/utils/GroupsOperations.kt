package pl.allegro.tech.servicemesh.envoycontrol.utils

import pl.allegro.tech.servicemesh.envoycontrol.groups.AccessLogFilterSettings
import pl.allegro.tech.servicemesh.envoycontrol.groups.AllServicesGroup
import pl.allegro.tech.servicemesh.envoycontrol.groups.CommunicationMode
import pl.allegro.tech.servicemesh.envoycontrol.groups.DependencySettings
import pl.allegro.tech.servicemesh.envoycontrol.groups.IncomingRateLimitEndpoint
import pl.allegro.tech.servicemesh.envoycontrol.groups.ListenersConfig
import pl.allegro.tech.servicemesh.envoycontrol.groups.Outgoing
import pl.allegro.tech.servicemesh.envoycontrol.groups.PathNormalizationPolicy
import pl.allegro.tech.servicemesh.envoycontrol.groups.ProxySettings
import pl.allegro.tech.servicemesh.envoycontrol.groups.ServicesGroup
import pl.allegro.tech.servicemesh.envoycontrol.groups.with
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.serviceDependencies

fun createServicesGroup(
    mode: CommunicationMode = CommunicationMode.XDS,
    serviceName: String = DEFAULT_SERVICE_NAME,
    discoveryServiceName: String = DEFAULT_DISCOVERY_SERVICE_NAME,
    dependencies: Array<Pair<String, Outgoing.TimeoutPolicy?>> = emptyArray(),
    rateLimitEndpoints: List<IncomingRateLimitEndpoint> = emptyList(),
    snapshotProperties: SnapshotProperties,
    listenersConfig: ListenersConfig? = createListenersConfig(snapshotProperties)
): ServicesGroup {
    return ServicesGroup(
        communicationMode = mode,
        serviceName = serviceName,
        discoveryServiceName = discoveryServiceName,
        proxySettings = ProxySettings().with(
            serviceDependencies = serviceDependencies(*dependencies),
            rateLimitEndpoints = rateLimitEndpoints
        ),
        pathNormalizationPolicy = PathNormalizationPolicy(),
        listenersConfig = listenersConfig
    )
}

fun createAllServicesGroup(
    mode: CommunicationMode = CommunicationMode.XDS,
    serviceName: String = DEFAULT_SERVICE_NAME,
    discoveryServiceName: String = DEFAULT_DISCOVERY_SERVICE_NAME,
    dependencies: Array<Pair<String, Outgoing.TimeoutPolicy?>> = emptyArray(),
    defaultServiceSettings: DependencySettings,
    listenersConfigExists: Boolean = true,
    snapshotProperties: SnapshotProperties
): AllServicesGroup {
    val listenersConfig = when (listenersConfigExists) {
        true -> createListenersConfig(snapshotProperties)
        false -> null
    }
    return AllServicesGroup(
        communicationMode = mode,
        serviceName = serviceName,
        discoveryServiceName = discoveryServiceName,
        proxySettings = ProxySettings().with(
            serviceDependencies = serviceDependencies(*dependencies),
            defaultServiceSettings = defaultServiceSettings
        ),
        pathNormalizationPolicy = PathNormalizationPolicy(),
        listenersConfig = listenersConfig
    )
}

fun createListenersConfig(
    snapshotProperties: SnapshotProperties,
    hasStaticSecretsDefined: Boolean = false
)
    : ListenersConfig {
    return ListenersConfig(
        ingressHost = INGRESS_HOST,
        ingressPort = INGRESS_PORT,
        egressHost = EGRESS_HOST,
        egressPort = EGRESS_PORT,
        accessLogFilterSettings = AccessLogFilterSettings(
            null,
            snapshotProperties.dynamicListeners.httpFilters.accessLog.filters
        ),
        hasStaticSecretsDefined = hasStaticSecretsDefined
    )
}
