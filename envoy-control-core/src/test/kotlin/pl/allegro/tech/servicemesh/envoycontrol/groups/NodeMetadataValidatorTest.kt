package pl.allegro.tech.servicemesh.envoycontrol.groups

import io.envoyproxy.envoy.api.v2.DiscoveryRequest
import io.grpc.Status
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.EnabledCommunicationModes
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.OutgoingPermissionsProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotProperties

class NodeMetadataValidatorTest {

    val validator = NodeMetadataValidator(SnapshotProperties().apply {
        outgoingPermissions = createOutgoingPermissions(enabled = true, servicesAllowedToUseWildcard = mutableSetOf("vis-1", "vis-2"))
    })

    @Test
    fun `should fail if service has no privilege to use wildcard`() {
        // given
        val node = node(
            serviceDependencies = setOf("*", "a", "b", "c"),
            serviceName = "regular-1"
        )
        val request = DiscoveryRequest.newBuilder().setNode(node).build()

        // expects
        assertThatExceptionOfType(AllDependenciesValidationException::class.java)
            .isThrownBy { validator.onV2StreamRequest(streamId = 123, request = request) }
            .satisfies {
                assertThat(it.status.description).isEqualTo(
                    "Blocked service regular-1 from using all dependencies. Only defined services can use all dependencies"
                )
                assertThat(it.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
            }
    }

    @Test
    fun `should not fail if service has privilege to use wildcard`() {
        // given
        val node = node(
            serviceDependencies = setOf("*", "a", "b", "c"),
            serviceName = "vis-1"
        )

        val request = DiscoveryRequest.newBuilder().setNode(node).build()

        // then
        assertDoesNotThrow { validator.onV2StreamRequest(123, request = request) }
    }

    @Test
    fun `should not fail if outgoing-permissions is disabled`() {
        // given
        val permissionsDisabledValidator = NodeMetadataValidator(SnapshotProperties().apply {
            outgoingPermissions = createOutgoingPermissions(enabled = false, servicesAllowedToUseWildcard = mutableSetOf("vis-1", "vis-2"))
        })
        val node = node(
            serviceDependencies = setOf("*", "a", "b", "c"),
            serviceName = "regular-1"
        )
        val request = DiscoveryRequest.newBuilder().setNode(node).build()

        // then
        assertDoesNotThrow { permissionsDisabledValidator.onV2StreamRequest(123, request = request) }
    }

    @ParameterizedTest
    @CsvSource(
        "false, true, true, ADS",
        "true, false, false, XDS",
        "false, false, false, XDS",
        "false, false, true, ADS"
    )
    fun `should fail if service wants to use mode which server doesn't support`(
        adsSupported: Boolean,
        xdsSupported: Boolean,
        ads: Boolean,
        modeNotSupportedName: String
    ) {
        // given
        val configurationModeValidator = NodeMetadataValidator(SnapshotProperties().apply {
            enabledCommunicationModes = createCommunicationMode(ads = adsSupported, xds = xdsSupported)
        })

        val node = node(
            ads = ads,
            serviceDependencies = setOf("a", "b", "c"),
            serviceName = "regular-1"
        )
        val request = DiscoveryRequest.newBuilder().setNode(node).build()

        // expects
        assertThatExceptionOfType(ConfigurationModeNotSupportedException::class.java)
            .isThrownBy { configurationModeValidator.onV2StreamRequest(streamId = 123, request = request) }
            .satisfies {
                assertThat(it.status.description).isEqualTo(
                    "Blocked service regular-1 from receiving updates. $modeNotSupportedName is not supported by server."
                )
                assertThat(it.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
            }
    }

    @ParameterizedTest
    @CsvSource(
        "true, true, true",
        "true, false, true",
        "false, true, false",
        "true, true, false"
    )
    fun `should do nothing if service wants to use mode supported by the server`(
        adsSupported: Boolean,
        xdsSupported: Boolean,
        ads: Boolean
    ) {
        // given
        val configurationModeValidator = NodeMetadataValidator(SnapshotProperties().apply {
            enabledCommunicationModes = createCommunicationMode(ads = adsSupported, xds = xdsSupported)
        })

        val node = node(
            ads = ads,
            serviceDependencies = setOf("a", "b", "c"),
            serviceName = "regular-1"
        )
        val request = DiscoveryRequest.newBuilder().setNode(node).build()

        // then
        assertDoesNotThrow { configurationModeValidator.onV2StreamRequest(123, request = request) }
    }

    private fun createOutgoingPermissions(
        enabled: Boolean = false,
        servicesAllowedToUseWildcard: MutableSet<String> = mutableSetOf()
    ): OutgoingPermissionsProperties {
        val outgoingPermissions = OutgoingPermissionsProperties()
        outgoingPermissions.enabled = enabled
        outgoingPermissions.servicesAllowedToUseWildcard = servicesAllowedToUseWildcard
        return outgoingPermissions
    }

    private fun createCommunicationMode(ads: Boolean = true, xds: Boolean = true): EnabledCommunicationModes {
        val enabledCommunicationModes = EnabledCommunicationModes()
        enabledCommunicationModes.ads = ads
        enabledCommunicationModes.xds = xds
        return enabledCommunicationModes
    }
}
