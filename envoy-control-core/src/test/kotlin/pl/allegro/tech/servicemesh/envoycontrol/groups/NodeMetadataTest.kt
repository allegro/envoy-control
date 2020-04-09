package pl.allegro.tech.servicemesh.envoycontrol.groups

import com.google.protobuf.Value
import com.google.protobuf.util.Durations
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

    @Test
    fun `should accept incoming settings with custom healthCheckPath`() {
        // given
        val proto = proxySettingsProto(
            incomingSettings = true,
            path = "/path",
            healthCheckPath = "/status/ping"
        )
        val incoming = proto.structValue?.fieldsMap?.get("incoming").toIncoming()

        // expects
        assertThat(incoming.healthCheck.clusterName).isEqualTo("local_service_health_check")
        assertThat(incoming.healthCheck.path).isEqualTo("/status/ping")
        assertThat(incoming.healthCheck.hasCustomHealthCheck()).isTrue()
    }

    @Test
    fun `should set empty healthCheckPath for incoming settings when healthCheckPath is empty`() {
        // given
        val proto = proxySettingsProto(
            incomingSettings = true,
            path = "/path"
        )
        val incoming = proto.structValue?.fieldsMap?.get("incoming").toIncoming()

        // expects
        assertThat(incoming.healthCheck.clusterName).isEqualTo("local_service_health_check")
        assertThat(incoming.healthCheck.path).isEqualTo("")
        assertThat(incoming.healthCheck.hasCustomHealthCheck()).isFalse()
    }

    @Test
    fun `should set healthCheckPath and healthCheckClusterName for incoming settings`() {
        // given
        val proto = proxySettingsProto(
            incomingSettings = true,
            path = "/path",
            healthCheckPath = "/status/ping",
            healthCheckClusterName = "local_service_health_check"
        )
        val incoming = proto.structValue?.fieldsMap?.get("incoming").toIncoming()

        // expects
        assertThat(incoming.healthCheck.clusterName).isEqualTo("local_service_health_check")
        assertThat(incoming.healthCheck.path).isEqualTo("/status/ping")
        assertThat(incoming.healthCheck.hasCustomHealthCheck()).isTrue()
    }

    @Test
    fun `should accept service dependency with idleTimeout defined`() {
        // given
        val proto = outgoingDependencyProto(service = "service-1", idleTimeout = "10s")
        val dependency = proto.toDependency() as ServiceDependency

        // expects
        assertThat(dependency.service).isEqualTo("service-1")
        assertThat(dependency.settings.timeoutPolicy!!.idleTimeout).isEqualTo(Durations.fromSeconds(10L))
    }

    @Test
    fun `should accept service dependency with requestTimeout defined`() {
        // given
        val proto = outgoingDependencyProto(service = "service-1", requestTimeout = "10s")
        val dependency = proto.toDependency() as ServiceDependency

        // expects
        assertThat(dependency.service).isEqualTo("service-1")
        assertThat(dependency.settings.timeoutPolicy!!.requestTimeout).isEqualTo(Durations.fromSeconds(10L))
    }

    @Test
    fun `should accept service dependency with idleTimeout and requestTimeout defined`() {
        // given
        val proto = outgoingDependencyProto(service = "service-1", idleTimeout = "10s", requestTimeout = "10s")
        val dependency = proto.toDependency() as ServiceDependency

        // expects
        assertThat(dependency.service).isEqualTo("service-1")
        assertThat(dependency.settings.timeoutPolicy!!.idleTimeout).isEqualTo(Durations.fromSeconds(10L))
        assertThat(dependency.settings.timeoutPolicy!!.requestTimeout).isEqualTo(Durations.fromSeconds(10L))
    }

    @Test
    fun `should reject configuration with number timeout format`() {
        // given
        val proto = Value.newBuilder().setNumberValue(10.0).build()

        // when
        val exception = assertThrows<NodeMetadataValidationException> { proto.toDuration() }

        // then
        assertThat(exception.status.description).isEqualTo("Timeout definition has number format" +
            " but should be in string format and ends with 's'")
        assertThat(exception.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
    }

    @Test
    fun `should reject configuration with incorrect string timeout format`() {
        // given
        val proto = Value.newBuilder().setStringValue("20").build()

        // when
        val exception = assertThrows<NodeMetadataValidationException> { proto.toDuration() }

        // then
        assertThat(exception.status.description).isEqualTo("Timeout definition has incorrect format: " +
            "Invalid duration string: 20")
        assertThat(exception.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
    }

    @Test
    fun `should return duration when correct configuration provided`() {
        // given
        val proto = Value.newBuilder().setStringValue("20s").build()

        // when
        val duration = proto.toDuration()

        // then
        assertThat(duration).isNotNull
        assertThat(duration!!.seconds).isEqualTo(20L)
    }

    @Test
    fun `should return null when empty value provided`() {
        // given
        val proto = Value.newBuilder().build()

        // when
        val duration = proto.toDuration()

        // then
        assertThat(duration).isNull()
    }
}
