package pl.allegro.tech.servicemesh.envoycontrol.groups

import io.envoyproxy.envoy.api.v2.DiscoveryRequest
import io.grpc.Status
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.ConfigurationMode
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.OutgoingPermissionsProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotProperties

class NodeMetadataValidatorTest {

    companion object {
        @JvmStatic
        fun configurationModeNotSupported() = listOf(
            Arguments.of(false, true, true, "ADS"),
            Arguments.of(true, false, false, "XDS"),
            Arguments.of(false, false, false, "Neither ADS nor XDS"),
            Arguments.of(false, false, true, "Neither ADS nor XDS")
        )

        @JvmStatic
        fun configurationModeSupported() = listOf(
            Arguments.of(true, true, true),
            Arguments.of(true, false, true),
            Arguments.of(false, true, false),
            Arguments.of(true, true, false)
        )
    }

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
            .isThrownBy { validator.onStreamRequest(streamId = 123, request = request) }
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

        // when
        validator.onStreamRequest(123, request = request)

        // then
        // no exception thrown
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

        // when
        permissionsDisabledValidator.onStreamRequest(123, request = request)

        // then
        // no exception thrown
    }

    @ParameterizedTest
    @MethodSource("configurationModeNotSupported")
    fun `should fail if service want to use mode which server doesn't support`(
        adsSupported: Boolean,
        xdsSupported: Boolean,
        ads: Boolean,
        modeNotSupportedName: String
    ) {
        // given
        val configurationModeValidator = NodeMetadataValidator(SnapshotProperties().apply {
            configurationMode = createConfigurationMode(ads = adsSupported, xds = xdsSupported)
        })

        val node = node(
            ads = ads,
            serviceDependencies = setOf("a", "b", "c"),
            serviceName = "regular-1"
        )
        val request = DiscoveryRequest.newBuilder().setNode(node).build()

        // expects
        assertThatExceptionOfType(ConfigurationModeNotSupportedException::class.java)
            .isThrownBy { configurationModeValidator.onStreamRequest(streamId = 123, request = request) }
            .satisfies {
                assertThat(it.status.description).isEqualTo(
                    "Blocked service regular-1 from receiving updates. $modeNotSupportedName is not supported by server."
                )
                assertThat(it.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
            }
    }

    @ParameterizedTest
    @MethodSource("configurationModeSupported")
    fun `should do nothing if service want to use mode which server supports`(
        adsSupported: Boolean,
        xdsSupported: Boolean,
        ads: Boolean
    ) {
        // given
        val configurationModeValidator = NodeMetadataValidator(SnapshotProperties().apply {
            configurationMode = createConfigurationMode(ads = adsSupported, xds = xdsSupported)
        })

        val node = node(
            ads = ads,
            serviceDependencies = setOf("a", "b", "c"),
            serviceName = "regular-1"
        )
        val request = DiscoveryRequest.newBuilder().setNode(node).build()

        // when
        configurationModeValidator.onStreamRequest(123, request = request)

        // then
        // no exception thrown
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

    private fun createConfigurationMode(ads: Boolean = true, xds: Boolean = true): ConfigurationMode {
        val configurationMode = ConfigurationMode()
        configurationMode.ads = ads
        configurationMode.xds = xds
        return configurationMode
    }
}
