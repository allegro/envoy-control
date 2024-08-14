package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.groups.CommunicationMode
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group
import pl.allegro.tech.servicemesh.envoycontrol.groups.ServicesGroup
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.IncomingPermissionsProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters.LuaMetadataProperty.BooleanPropertyLua
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters.LuaMetadataProperty.ListPropertyLua
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters.LuaMetadataProperty.NumberPropertyLua
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters.LuaMetadataProperty.StringPropertyLua
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters.LuaMetadataProperty.StructPropertyLua

internal class LuaFilterFactoryTest {
    val properties = SnapshotProperties().also { it.incomingPermissions = IncomingPermissionsProperties() }

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
        val factory = LuaFilterFactory(properties)

        // when
        val metadata = factory.ingressScriptsMetadata(group, currentZone = "dc1")
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
    fun `should create filter metadata with serviceId`() {
        // given
        val expectedServiceId = 777
        val group = ServicesGroup(communicationMode = CommunicationMode.XDS, serviceId = expectedServiceId)
        val luaFilterFactory = LuaFilterFactory(properties)

        // when
        val metadata = luaFilterFactory.ingressScriptsMetadata(group, currentZone = "dc1")

        val actualServiceId = metadata
            .getFilterMetadataOrThrow("envoy.filters.http.lua")
            .getFieldsOrThrow("service_id")
            .stringValue

        // then
        assertThat(actualServiceId).isEqualTo(expectedServiceId.toString())
    }

    @Test
    fun `should create filter metadata with empty serviceId`() {
        // given
        val group = ServicesGroup(communicationMode = CommunicationMode.XDS)
        val luaFilterFactory = LuaFilterFactory(properties)

        // when
        val metadata = luaFilterFactory.ingressScriptsMetadata(group, currentZone = "dc1")

        val actualServiceId = metadata
            .getFilterMetadataOrThrow("envoy.filters.http.lua")
            .getFieldsOrThrow("service_id")
            .stringValue

        // then
        assertThat(actualServiceId).isEmpty()
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
        val factory = LuaFilterFactory(properties)

        // when
        val luaMetadata = factory.ingressScriptsMetadata(group, customMetadata, "dc1")
            .getFilterMetadataOrThrow("envoy.filters.http.lua").fieldsMap

        // then
        assertThat(luaMetadata["flags"]).isEqualTo(customMetadata["flags"]?.toValue())
        assertThat(luaMetadata["list-value"]).isEqualTo(customMetadata["list-value"]?.toValue())
        assertThat(luaMetadata["count"]).isEqualTo(customMetadata["count"]?.toValue())
    }

    @Test
    fun `should create metadata with current zone`() {
        // given
        val expectedServiceName = "service-1"
        val expectedDiscoveryServiceName = "consul-service-1"
        val expectedCurrentZone = "dc1"
        val group: Group = ServicesGroup(
            communicationMode = CommunicationMode.XDS,
            serviceName = expectedServiceName,
            discoveryServiceName = expectedDiscoveryServiceName
        )
        val factory = LuaFilterFactory(properties)

        // when
        val luaMetadata = factory.ingressScriptsMetadata(group, StructPropertyLua(), expectedCurrentZone)
            .getFilterMetadataOrThrow("envoy.filters.http.lua").fieldsMap

        // then
        assertThat(luaMetadata["current_zone"]?.stringValue).isEqualTo(expectedCurrentZone)
    }
}
