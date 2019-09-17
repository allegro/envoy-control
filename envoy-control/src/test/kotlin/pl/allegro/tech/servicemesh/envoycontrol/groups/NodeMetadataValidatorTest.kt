package pl.allegro.tech.servicemesh.envoycontrol.groups

import io.envoyproxy.envoy.api.v2.DiscoveryRequest
import io.grpc.Status
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.OutgoingPermissionsProperties

class NodeMetadataValidatorTest {
    val validator = NodeMetadataValidator(OutgoingPermissionsProperties().apply {
        enabled = true
        servicesAllowedToUseWildcard = mutableSetOf("vis-1", "vis-2")
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
        val permissionsDisabledValidator = NodeMetadataValidator(OutgoingPermissionsProperties().apply {
            enabled = false
            servicesAllowedToUseWildcard = mutableSetOf("vis-1", "vis-2")
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
}
