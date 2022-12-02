package pl.allegro.tech.servicemesh.envoycontrol.groups

import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest
import io.grpc.Status
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.EnabledCommunicationModes
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.IncomingPermissionsProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.OutgoingPermissionsProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotProperties
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest as DiscoveryRequestV3

class NodeMetadataValidatorTest {

    private val validator = NodeMetadataValidator(SnapshotProperties().apply {
        outgoingPermissions = createOutgoingPermissions(
            enabled = true,
            servicesAllowedToUseWildcard = mutableSetOf("vis-1", "vis-2"),
            tagPrefix = "tag:"
        )
        incomingPermissions = createIncomingPermissions(
            enabled = true,
            servicesAllowedToUseWildcard = mutableSetOf("vis-1", "vis-2")
        )
    })

    @Test
    fun `should fail if service has no privilege to use incoming wildcard`() {
        // given
        val node = nodeV3(
            serviceName = "regular-1",
            incomingSettings = true,
            clients = listOf("*")
        )
        val request = DiscoveryRequestV3.newBuilder().setNode(node).build()

        // then
        validator.assertThrow(
            request,
            WildcardPrincipalValidationException::class.java,
            "Blocked service regular-1 from allowing everyone in incoming permissions. Only defined services can use that."
        )
    }

    @Test
    fun `should fail if service mixes incoming wildcard and normal permissions`() {
        // given
        val node = nodeV3(
            serviceName = "vis-1",
            incomingSettings = true,
            clients = listOf("*", "something")
        )
        val request = DiscoveryRequestV3.newBuilder().setNode(node).build()

        // expects
        validator.assertThrow(
            request,
            WildcardPrincipalMixedWithOthersValidationException::class.java,
            "Blocked service vis-1 from allowing everyone in incoming permissions. Either a wildcard or a list of clients must be provided."
        )
    }

    @Test
    fun `should fail if service has no privilege to use outgoing wildcard`() {
        // given
        val node = nodeV3(
            serviceDependencies = setOf("*", "a", "b", "c"),
            serviceName = "regular-1"
        )
        val request = DiscoveryRequestV3.newBuilder().setNode(node).build()

        // expects
        validator.assertThrow(
            request,
            AllDependenciesValidationException::class.java,
            "Blocked service regular-1 from using all dependencies. Only defined services can use all dependencies"
        )
    }

    @Test
    fun `should not fail if service has privilege to use incoming wildcard`() {
        // given
        val node = nodeV3(
            serviceName = "vis-1",
            incomingSettings = true,
            clients = listOf("*")
        )

        val request = DiscoveryRequestV3.newBuilder().setNode(node).build()

        // then
        validator.notThrow(request)
    }

    @Test
    fun `should not fail if service has privilege to use outgoing wildcard`() {
        // given
        val node = nodeV3(
            serviceDependencies = setOf("*", "a", "b", "c"),
            serviceName = "vis-1"
        )

        val request = DiscoveryRequestV3.newBuilder().setNode(node).build()

        // then
        validator.notThrow(request)
    }

    @Test
    fun `should not fail if outgoing-permissions is disabled`() {
        // given
        val permissionsDisabledValidator = NodeMetadataValidator(SnapshotProperties().apply {
            outgoingPermissions = createOutgoingPermissions(
                enabled = false,
                servicesAllowedToUseWildcard = mutableSetOf("vis-1", "vis-2")
            )
        })
        val node = nodeV3(
            serviceDependencies = setOf("*", "a", "b", "c"),
            serviceName = "regular-1"
        )
        val request = DiscoveryRequestV3.newBuilder().setNode(node).build()

        // then
        permissionsDisabledValidator.notThrow(request)
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

        val node = nodeV3(
            ads = ads,
            serviceDependencies = setOf("a", "b", "c"),
            serviceName = "regular-1"
        )
        val request = DiscoveryRequestV3.newBuilder().setNode(node).build()

        // expects
        configurationModeValidator.assertThrow(
            request,
            ConfigurationModeNotSupportedException::class.java,
            "Blocked service regular-1 from receiving updates. $modeNotSupportedName is not supported by server."
        )
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

        val node = nodeV3(
            ads = ads,
            serviceDependencies = setOf("a", "b", "c"),
            serviceName = "regular-1"
        )
        val request = DiscoveryRequestV3.newBuilder().setNode(node).build()

        // then
        validator.notThrow(request)
    }

    @Test
    fun `should fail when service name is empty`() {
        // given
        val requireServiceNameValidator = NodeMetadataValidator(SnapshotProperties().apply {
            requireServiceName = true
        })
        val node = nodeV3(
            serviceDependencies = setOf("*", "a", "b", "c"),
            serviceName = ""
        )
        val request = DiscoveryRequestV3.newBuilder().setNode(node).build()

        // expects
        requireServiceNameValidator.assertThrow(
            request,
            ServiceNameNotProvidedException::class.java,
            "Service name has not been provided."
        )
    }

    @Test
    fun `should not throw an exception when service name is empty and validation disabled`() {
        // given
        val node = nodeV3(
            serviceDependencies = setOf("a", "b", "c"),
            serviceName = ""
        )
        val request = DiscoveryRequestV3.newBuilder().setNode(node).build()

        // then
        validator.notThrow(request)
    }

    @Test
    fun `should throw an exception when rate limit is invalid`() {
        // given
        val node = nodeV3(
            incomingSettings = true,
            rateLimit = "0/j"
        )
        val request = DiscoveryRequestV3.newBuilder()
            .setNode(node)
            .build()

        // then
        validator.assertThrow(
            request,
            RateLimitIncorrectValidationException::class.java,
            "Rate limit value: 0/j is incorrect."
        )
    }

    @Test
    fun `should throw an exception when service is using tag dependency without prefix`() {
        // given
        val node = nodeV3(
            serviceName = "tag-service",
            tagDependencies = setOf("tag:xyz", "abc")
        )
        val request = DiscoveryRequestV3.newBuilder()
            .setNode(node)
            .build()

        // then
        validator.assertThrow(
            request,
            TagDependencyValidationException::class.java,
            "Blocked service tag-service from using tag dependencies [abc]. Only allowed tags are supported."
        )
    }

    @Test
    fun `should not throw an exception when service is using tag dependency with prefix`() {
        // given
        val node = nodeV3(
            serviceName = "tag-service",
            tagDependencies = setOf("tag:xyz", "tag:abc")
        )
        val request = DiscoveryRequestV3.newBuilder()
            .setNode(node)
            .build()

        // then
        validator.notThrow(request)
    }

    private fun createIncomingPermissions(
        enabled: Boolean = false,
        servicesAllowedToUseWildcard: MutableSet<String> = mutableSetOf()
    ): IncomingPermissionsProperties = IncomingPermissionsProperties().apply {
        this.enabled = enabled
        this.tlsAuthentication.servicesAllowedToUseWildcard = servicesAllowedToUseWildcard
    }

    private fun createOutgoingPermissions(
        enabled: Boolean = false,
        servicesAllowedToUseWildcard: MutableSet<String> = mutableSetOf(),
        tagPrefix: String = ""
    ): OutgoingPermissionsProperties = OutgoingPermissionsProperties().apply {
        this.enabled = enabled
        this.servicesAllowedToUseWildcard = servicesAllowedToUseWildcard
        this.tagPrefix = tagPrefix
    }

    private fun createCommunicationMode(
        ads: Boolean = true,
        xds: Boolean = true
    ): EnabledCommunicationModes = EnabledCommunicationModes().apply {
        this.ads = ads
        this.xds = xds
    }


    private fun NodeMetadataValidator.assertThrow(
        request: DiscoveryRequest,
        exceptionClass: Class<out NodeMetadataValidationException>,
        description: String,
    ) = assertThatExceptionOfType(exceptionClass)
            .isThrownBy { this.onV3StreamRequest(123, request) }
            .matches { it.status.description == description }
            .matches { it.status.code == Status.Code.INVALID_ARGUMENT }


    private fun NodeMetadataValidator.notThrow(request: DiscoveryRequest) = assertDoesNotThrow {
        this.onV3StreamRequest(123, request)
    }
}
