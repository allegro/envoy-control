package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.groups.CommunicationMode
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group
import pl.allegro.tech.servicemesh.envoycontrol.groups.ServicesGroup
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.IncomingPermissionsProperties

internal class LuaFilterFactoryTest {

    @Test
    fun `should create metadata with service name`() {
        // given
        val expectedServiceName = "service-1"
        val group: Group = ServicesGroup(CommunicationMode.XDS, expectedServiceName)
        val factory = LuaFilterFactory(IncomingPermissionsProperties())

        // when
        val metadata = factory.ingressScriptsMetadata(group)
        val givenServiceName = metadata
            .getFilterMetadataOrThrow("envoy.filters.http.lua")
            .getFieldsOrThrow("service_name")
            .stringValue

        // then
        assertThat(givenServiceName).isEqualTo(expectedServiceName)
    }
}
