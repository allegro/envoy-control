package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.groups.AccessLogFilterSettings
import pl.allegro.tech.servicemesh.envoycontrol.groups.CommunicationMode
import pl.allegro.tech.servicemesh.envoycontrol.groups.ListenersConfig
import pl.allegro.tech.servicemesh.envoycontrol.groups.Outgoing
import pl.allegro.tech.servicemesh.envoycontrol.groups.ProxySettings
import pl.allegro.tech.servicemesh.envoycontrol.groups.ServicesGroup
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.AccessLogFiltersProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.GlobalSnapshot
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.createClusters
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters.EnvoyHttpFilters
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.serviceDependency
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.tagDependency

class EnvoyListenersFactoryTest {

    companion object {
        val serviceGroup = ServicesGroup(
            communicationMode = CommunicationMode.ADS,
            serviceName = "service-name",
            discoveryServiceName = "service-name",
            listenersConfig = ListenersConfig(
                ingressHost = "10.10.10.10",
                ingressPort = 8888,
                egressHost = "11.11.11.11",
                egressPort = 9999,
                accessLogFilterSettings = AccessLogFilterSettings(null, AccessLogFiltersProperties()),
                useTransparentProxy = true
            )
        )
    }

    @Test
    fun `should return egress http proxy virtual listener with service dependency`() {
        // given
        val properties = SnapshotProperties()
        val factory = EnvoyListenersFactory(properties, EnvoyHttpFilters(emptyList(), emptyList()))
        val group = serviceGroup.copy(
            proxySettings = ProxySettings(
                outgoing = Outgoing(
                    serviceDependencies = listOf(serviceDependency("service-A", 33))
                )
            )
        )
        val services = listOf("service-A", "service-B", "service-C")
        val globalSnapshot = GlobalSnapshot(
            clusters = createClusters(properties, services),
            allServicesNames = services.toSet(),
            endpoints = emptyMap(),
            clusterConfigurations = emptyMap(),
            securedClusters = emptyMap(),
            tags = emptyMap()
        )

        // when
        val listeners = factory.createListeners(group, globalSnapshot)

        // then
        Assertions.assertThat(listeners)
            .extracting<String> { it.name }
            .contains("0.0.0.0:80")
    }

    @Test
    fun `should return egress http proxy virtual listener with tag dependency`() {
        // given
        val properties = SnapshotProperties()
        val factory = EnvoyListenersFactory(properties, EnvoyHttpFilters(emptyList(), emptyList()))
        val group = serviceGroup.copy(
            proxySettings = ProxySettings(
                outgoing = Outgoing(
                    tagDependencies = listOf(tagDependency("tag", 33))
                )
            )
        )
        val services = listOf("service-A", "service-B", "service-C")
        val globalSnapshot = GlobalSnapshot(
            clusters = createClusters(properties, services),
            allServicesNames = services.toSet(),
            endpoints = emptyMap(),
            clusterConfigurations = emptyMap(),
            securedClusters = emptyMap(),
            tags = mapOf("service-B" to setOf("tag"))
        )

        // when
        val listeners = factory.createListeners(group, globalSnapshot)

        // then
        Assertions.assertThat(listeners)
            .extracting<String> { it.name }
            .contains("0.0.0.0:80")
    }
}
