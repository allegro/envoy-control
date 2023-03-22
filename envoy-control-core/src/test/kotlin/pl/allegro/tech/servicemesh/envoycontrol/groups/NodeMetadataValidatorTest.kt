package pl.allegro.tech.servicemesh.envoycontrol.groups

import io.grpc.Status
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.IncomingPermissionsProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.OutgoingPermissionsProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotProperties
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest as DiscoveryRequestV3

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
        val node = nodeV3(
            serviceName = "regular-1",
            incomingSettings = true,
            clients = listOf("*")
        )
        val request = DiscoveryRequestV3.newBuilder().setNode(node).build()

        // when
        val exception = catchThrowable { validator.onV3StreamRequest(streamId = 123, request = request) }

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
        val node = nodeV3(
            serviceName = "vis-1",
            incomingSettings = true,
            clients = listOf("*", "something")
        )
        val request = DiscoveryRequestV3.newBuilder().setNode(node).build()

        // when
        val exception = catchThrowable { validator.onV3StreamRequest(streamId = 123, request = request) }

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
        val node = nodeV3(
            serviceDependencies = setOf("*", "a", "b", "c"),
            serviceName = "regular-1"
        )
        val request = DiscoveryRequestV3.newBuilder().setNode(node).build()

        // when
        val exception = catchThrowable { validator.onV3StreamRequest(streamId = 123, request = request) }

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
        val node = nodeV3(
            serviceName = "vis-1",
            incomingSettings = true,
            clients = listOf("*")
        )

        val request = DiscoveryRequestV3.newBuilder().setNode(node).build()

        // then
        assertDoesNotThrow { validator.onV3StreamRequest(123, request = request) }
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
        assertDoesNotThrow { validator.onV3StreamRequest(123, request = request) }
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
        assertDoesNotThrow { permissionsDisabledValidator.onV3StreamRequest(123, request = request) }
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
        assertThatExceptionOfType(ServiceNameNotProvidedException::class.java)
            .isThrownBy { requireServiceNameValidator.onV3StreamRequest(streamId = 123, request = request) }
            .matches { it.status.description == "Service name has not been provided." }
            .matches { it.status.code == Status.Code.INVALID_ARGUMENT }
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
        assertDoesNotThrow { validator.onV3StreamRequest(123, request = request) }
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
        assertThatExceptionOfType(RateLimitIncorrectValidationException::class.java)
            .isThrownBy { validator.onV3StreamRequest(123, request = request) }
            .matches { it.status.description == "Rate limit value: 0/j is incorrect." }
            .matches { it.status.code == Status.Code.INVALID_ARGUMENT }
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
}
