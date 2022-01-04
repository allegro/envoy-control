package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.groups.AccessLogFilterSettings
import pl.allegro.tech.servicemesh.envoycontrol.groups.CommunicationMode
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group
import pl.allegro.tech.servicemesh.envoycontrol.groups.ListenersConfig
import pl.allegro.tech.servicemesh.envoycontrol.groups.ServicesGroup
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.IncomingPermissionsProperties

internal class LuaFilterFactoryTest {

    @Test
    fun `should create metadata with service name, host and port`() {
        // given
        val expectedServiceName = "service-1"
        val expectedIngressHost = "127.0.0.1"
        val expectedIngressPort = 3000
        val group: Group = ServicesGroup(
            communicationMode = CommunicationMode.XDS,
            serviceName = expectedServiceName,
            listenersConfig = ListenersConfig(
                ingressHost = "127.0.0.1",
                ingressPort = expectedIngressPort,
                egressHost = expectedIngressHost,
                egressPort = 3001,
                accessLogFilterSettings = AccessLogFilterSettings(null)
            )
        )
        val factory = LuaFilterFactory(IncomingPermissionsProperties())

        // when
        val metadata = factory.ingressScriptsMetadata(group)
        val givenServiceName = metadata
            .getFilterMetadataOrThrow("envoy.filters.http.lua")
            .getFieldsOrThrow("service_name")
            .stringValue
        val givenIngressHost = metadata
            .getFilterMetadataOrThrow("envoy.filters.http.lua")
            .getFieldsOrThrow("ingress_host")
            .stringValue
        val givenIngressPort = metadata
            .getFilterMetadataOrThrow("envoy.filters.http.lua")
            .getFieldsOrThrow("ingress_port")
            .stringValue

        // then
        assertThat(givenServiceName).isEqualTo(expectedServiceName)
        assertThat(givenIngressHost).isEqualTo(expectedIngressHost)
        assertThat(givenIngressPort).isEqualTo(expectedIngressPort.toString())
    }
}
