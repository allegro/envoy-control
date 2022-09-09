package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.groups.CommunicationMode
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group
import pl.allegro.tech.servicemesh.envoycontrol.groups.ServicesGroup
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.IncomingPermissionsProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters.LuaMetadataProperty.StructPropertyLua
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters.LuaMetadataProperty.ListPropertyLua
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters.LuaMetadataProperty.StringPropertyLua
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters.LuaMetadataProperty.BooleanPropertyLua
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters.LuaMetadataProperty.NumberPropertyLua

internal class LuaFilterFactoryTest {

    @Test
    fun `should create metadata with service name and discovery service name`() {
        // given
        val expectedServiceName = "service-1"
        val expectedDiscoveryServiceName = "consul-service-1"
        val group: Group = ServicesGroup(
            communicationMode = CommunicationMode.XDS,
            serviceName = expectedServiceName,
            discoveryServiceName = expectedDiscoveryServiceName
        )
        val factory = LuaFilterFactory(IncomingPermissionsProperties())

        // when
        val metadata = factory.ingressScriptsMetadata(group)
        val givenServiceName = metadata
            .getFilterMetadataOrThrow("envoy.filters.http.lua")
            .getFieldsOrThrow("service_name")
            .stringValue

        val givenDiscoveryServiceName = metadata
            .getFilterMetadataOrThrow("envoy.filters.http.lua")
            .getFieldsOrThrow("discovery_service_name")
            .stringValue

        // then
        assertThat(givenServiceName).isEqualTo(expectedServiceName)
        assertThat(givenDiscoveryServiceName).isEqualTo(expectedDiscoveryServiceName)
    }

    @Test
    fun `should create metadata with given customMetadata`() {
        // given
        val customMetadata = StructPropertyLua(
            "flags" to StructPropertyLua(
                "x-enabled" to BooleanPropertyLua.TRUE,
                "y-enabled" to BooleanPropertyLua.FALSE
            ),
            "list-value" to ListPropertyLua(StringPropertyLua("value1"), StringPropertyLua("value2")),
            "count" to NumberPropertyLua(1.0)
        )

        val expectedServiceName = "service-1"
        val expectedDiscoveryServiceName = "consul-service-1"
        val group: Group = ServicesGroup(
            communicationMode = CommunicationMode.XDS,
            serviceName = expectedServiceName,
            discoveryServiceName = expectedDiscoveryServiceName
        )
        val factory = LuaFilterFactory(IncomingPermissionsProperties())

        // when
        val luaMetadata = factory.ingressScriptsMetadata(group, customMetadata)
            .getFilterMetadataOrThrow("envoy.filters.http.lua").fieldsMap

        // then
        assertThat(luaMetadata["flags"]).isEqualTo(customMetadata["flags"]?.toValue())
        assertThat(luaMetadata["list-value"]).isEqualTo(customMetadata["list-value"]?.toValue())
        assertThat(luaMetadata["count"]).isEqualTo(customMetadata["count"]?.toValue())
    }
}
