package pl.allegro.tech.servicemesh.envoycontrol.groups

import io.grpc.Status
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class NodeMetadataTest {

    @Test
    fun `should reject endpoint with both path and pathPrefix defined`() {
        // given
        val proto = incomingEndpointProto(path = "/path", pathPrefix = "/prefix")

        // expects
        val exception = assertThrows<NodeMetadataValidationException> { proto.toIncomingEndpoint() }
        assertThat(exception.status.description).isEqualTo("Precisely one of 'path' and 'pathPrefix' field is allowed")
        assertThat(exception.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
    }

    @Test
    fun `should reject endpoint with no path or pathPrefix defined`() {
        // given
        val proto = incomingEndpointProto(path = null, pathPrefix = null)

        // expects
        val exception = assertThrows<NodeMetadataValidationException> { proto.toIncomingEndpoint() }
        assertThat(exception.status.description).isEqualTo("One of 'path' or 'pathPrefix' field is required")
        assertThat(exception.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
    }

    @Test
    fun `should accept endpoint with both path and pathPrefix defined but prefix is null`() {
        // given
        val proto = incomingEndpointProto(path = "/path", pathPrefix = null, includeNullFields = true)

        // when
        val result = proto.toIncomingEndpoint()

        // then
        // no exception thrown
        assertThat(result.path).isEqualTo("/path")
        assertThat(result.pathMatchingType).isEqualTo(PathMatchingType.PATH)
    }

    @Test
    fun `should accept endpoint with both path and pathPrefix defined but path is null`() {
        // given
        val proto = incomingEndpointProto(path = null, pathPrefix = "/prefix", includeNullFields = true)

        // when
        val result = proto.toIncomingEndpoint()

        // then
        // no exception thrown
        assertThat(result.path).isEqualTo("/prefix")
        assertThat(result.pathMatchingType).isEqualTo(PathMatchingType.PATH_PREFIX)
    }

    @Test
    fun `should reject dependency with neither service nor domain field defined`() {
        // given
        val proto = outgoingDependencyProto()

        // expects
        val exception = assertThrows<NodeMetadataValidationException> { proto.toDependency() }
        assertThat(exception.status.description)
            .isEqualTo("Define either 'service' or 'domain' as an outgoing dependency")
        assertThat(exception.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
    }

    @Test
    fun `should reject dependency with both service and domain fields defined`() {
        // given
        val proto = outgoingDependencyProto(service = "service", domain = "http://domain")

        // expects
        val exception = assertThrows<NodeMetadataValidationException> { proto.toDependency() }
        assertThat(exception.status.description)
            .isEqualTo("Define either 'service' or 'domain' as an outgoing dependency")
        assertThat(exception.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
    }

    @Test
    fun `should reject dependency with unsupported protocol in domain field `() {
        // given
        val proto = outgoingDependencyProto(domain = "ftp://domain")

        // expects
        val exception = assertThrows<NodeMetadataValidationException> { proto.toDependency() }
        assertThat(exception.status.description)
            .isEqualTo("Unsupported protocol for domain dependency for domain ftp://domain")
        assertThat(exception.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
    }

    @Test
    fun `should check if dependency for service is defined`() {
        // given
        val outgoing = Outgoing(listOf(ServiceDependency(
            service = "service-first",
            settings = DependencySettings(handleInternalRedirect = true)
        )))

        // expects
        assertThat(outgoing.containsDependencyForService("service-first")).isTrue()
        assertThat(outgoing.containsDependencyForService("service-second")).isFalse()
    }

    @Test
    fun `should accept domain dependency`() {
        // given
        val proto = outgoingDependencyProto(domain = "http://domain")

        // expects
        val dependency = proto.toDependency()
        assertThat(dependency).isInstanceOf(DomainDependency::class.java)
        assertThat((dependency as DomainDependency).domain).isEqualTo("http://domain")
    }

    @Test
    fun `should accept service dependency`() {
        // given
        val proto = outgoingDependencyProto(service = "my-service")

        // expects
        val dependency = proto.toDependency()
        assertThat(dependency).isInstanceOf(ServiceDependency::class.java)
        assertThat((dependency as ServiceDependency).service).isEqualTo("my-service")
    }

    @Test
    fun `should return correct host and default port for domain dependency`() {
        // given
        val proto = outgoingDependencyProto(domain = "http://domain")
        val dependency = proto.toDependency() as DomainDependency

        // expects
        assertThat(dependency.getHost()).isEqualTo("domain")
        assertThat(dependency.getPort()).isEqualTo(80)
    }

    @Test
    fun `should return custom port for domain dependency if it was defined`() {
        // given
        val proto = outgoingDependencyProto(domain = "http://domain:1234")
        val dependency = proto.toDependency() as DomainDependency

        // expects
        assertThat(dependency.getPort()).isEqualTo(1234)
    }

    @Test
    fun `should return correct names for domain dependency without port specified`() {
        // given
        val proto = outgoingDependencyProto(domain = "http://domain.pl")
        val dependency = proto.toDependency() as DomainDependency

        // expects
        assertThat(dependency.getClusterName()).isEqualTo("domain_pl_80")
        assertThat(dependency.getRouteDomain()).isEqualTo("domain.pl")
    }

    @Test
    fun `should return correct names for domain dependency with port specified`() {
        // given
        val proto = outgoingDependencyProto(domain = "http://domain.pl:80")
        val dependency = proto.toDependency() as DomainDependency

        // expects
        assertThat(dependency.getClusterName()).isEqualTo("domain_pl_80")
        assertThat(dependency.getRouteDomain()).isEqualTo("domain.pl:80")
    }

    @Test
    fun `should accept service dependency with redirect policy defined`() {
        // given
        val proto = outgoingDependencyProto(service = "service-1", handleInternalRedirect = true)
        val dependency = proto.toDependency() as ServiceDependency

        // expects
        assertThat(dependency.service).isEqualTo("service-1")
        assertThat(dependency.settings.handleInternalRedirect).isEqualTo(true)
    }
}
