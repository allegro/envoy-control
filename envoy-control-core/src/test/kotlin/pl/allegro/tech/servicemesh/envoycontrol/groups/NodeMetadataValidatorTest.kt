package pl.allegro.tech.servicemesh.envoycontrol.groups

import io.envoyproxy.envoy.api.v2.DiscoveryRequest
import io.grpc.Status
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.EnabledCommunicationModes
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.IncomingPermissionsProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.OutgoingPermissionsProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotProperties

class NodeMetadataValidatorTest {

    val validator = NodeMetadataValidator(SnapshotProperties().apply {
        outgoingPermissions = createOutgoingPermissions(
                enabled = true,
                servicesAllowedToUseWildcard = mutableSetOf("vis-1", "vis-2")
        )
        incomingPermissions = createIncomingPermissions(
                enabled = true,
                servicesAllowedToUseWildcard = mutableSetOf("vis-1", "vis-2")
        )
    })

    @Test
    fun `should fail if service has no privilege to use incoming wildcard`() {
        // given
        val node = node(
                serviceName = "regular-1",
                incomingSettings = true,
                clients = listOf("*")
        )
        val request = DiscoveryRequest.newBuilder().setNode(node).build()

        // when
        val exception = catchThrowable { validator.onV2StreamRequest(streamId = 123, request = request) }

        // then
        assertThat(exception).isInstanceOf(WildcardPrincipalValidationException::class.java)
        val validationException = exception as WildcardPrincipalValidationException
        assertThat(validationException.status.description)
            .isEqualTo("Blocked service regular-1 from allowing everyone in incoming permissions. Only defined services can use that.")
        assertThat(validationException.status.code)
            .isEqualTo(Status.Code.INVALID_ARGUMENT)
    }

    @Test
    fun `should fail if service mixes incoming wildcard and normal permissions`() {
        // given
        val node = node(
                serviceName = "vis-1",
                incomingSettings = true,
                clients = listOf("*", "something")
        )
        val request = DiscoveryRequest.newBuilder().setNode(node).build()

        // when
        val exception = catchThrowable { validator.onV2StreamRequest(streamId = 123, request = request) }

        // expects
        assertThat(exception).isInstanceOf(WildcardPrincipalMixedWithOthersValidationException::class.java)
        val validationException = exception as WildcardPrincipalMixedWithOthersValidationException
        assertThat(validationException.status.description)
            .isEqualTo("Blocked service vis-1 from allowing everyone in incoming permissions. Either a wildcard or a list of clients must be provided.")
        assertThat(validationException.status.code)
            .isEqualTo(Status.Code.INVALID_ARGUMENT)
    }

    @Test
    fun `should fail if service has no privilege to use outgoing wildcard`() {
        // given
        val node = node(
            serviceDependencies = setOf("*", "a", "b", "c"),
            serviceName = "regular-1"
        )
        val request = DiscoveryRequest.newBuilder().setNode(node).build()

        // when
        val exception = catchThrowable { validator.onV2StreamRequest(streamId = 123, request = request) }

        // expects
        assertThat(exception).isInstanceOf(AllDependenciesValidationException::class.java)
        val validationException = exception as AllDependenciesValidationException
        assertThat(validationException.status.description)
            .isEqualTo("Blocked service regular-1 from using all dependencies. Only defined services can use all dependencies")
        assertThat(validationException.status.code)
            .isEqualTo(Status.Code.INVALID_ARGUMENT)
    }

    @Test
    fun `should not fail if service has privilege to use incoming wildcard`() {
        // given
        val node = node(
                serviceName = "vis-1",
                incomingSettings = true,
                clients = listOf("*")
        )

        val request = DiscoveryRequest.newBuilder().setNode(node).build()

        // then
        assertDoesNotThrow { validator.onV2StreamRequest(123, request = request) }
    }

    @Test
    fun `should not fail if service has privilege to use outgoing wildcard`() {
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

        // when
        val exception = catchThrowable { configurationModeValidator.onV2StreamRequest(streamId = 123, request = request) }

        // expects
        assertThat(exception).isInstanceOf(ConfigurationModeNotSupportedException::class.java)
        val validationException = exception as ConfigurationModeNotSupportedException
        assertThat(validationException.status.description)
            .isEqualTo("Blocked service regular-1 from receiving updates. $modeNotSupportedName is not supported by server.")
        assertThat(validationException.status.code)
            .isEqualTo(Status.Code.INVALID_ARGUMENT)
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

    private fun createIncomingPermissions(
            enabled: Boolean = false,
            servicesAllowedToUseWildcard: MutableSet<String> = mutableSetOf()
    ): IncomingPermissionsProperties {
        val incomingPermissions = IncomingPermissionsProperties()
        incomingPermissions.enabled = enabled
        incomingPermissions.tlsAuthentication.servicesAllowedToUseWildcard = servicesAllowedToUseWildcard
        return incomingPermissions
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
