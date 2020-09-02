package pl.allegro.tech.servicemesh.envoycontrol.groups

import com.google.protobuf.Value
import com.google.protobuf.util.Durations
import io.envoyproxy.envoy.config.filter.accesslog.v2.ComparisonFilter
import io.grpc.Status
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.ObjectAssert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters.AccessLogFilterFactory

@Suppress("LargeClass")
class NodeMetadataTest {

    companion object {
        @JvmStatic
        fun validStatusCodeFilterData() = listOf(
            Arguments.of("Le:123", ComparisonFilter.Op.LE, 123),
            Arguments.of("EQ:400", ComparisonFilter.Op.EQ, 400),
            Arguments.of("gE:324", ComparisonFilter.Op.GE, 324),
            Arguments.of("LE:200", ComparisonFilter.Op.LE, 200)
        )

        @JvmStatic
        fun invalidStatusCodeFilterData() = listOf(
            Arguments.of("LT:123"),
            Arguments.of("equal:400"),
            Arguments.of("eq:24"),
            Arguments.of("GT:200"),
            Arguments.of("testeq:400test"),
            Arguments.of("")
        )
    }

    private val defaultDependencySettings = DependencySettings(
        handleInternalRedirect = false,
        timeoutPolicy = Outgoing.TimeoutPolicy(
            idleTimeout = Durations.fromMillis(1000),
            requestTimeout = Durations.fromMillis(3000)
        )
    )

    private fun snapshotProperties(
        allServicesDependenciesIdentifier: String = "*",
        handleInternalRedirect: Boolean = false,
        idleTimeout: String = "120s",
        requestTimeout: String = "120s"
    ) = SnapshotProperties().apply {
        outgoingPermissions.allServicesDependencies.identifier = allServicesDependenciesIdentifier
        egress.handleInternalRedirect = handleInternalRedirect
        egress.commonHttp.idleTimeout = java.time.Duration.ofNanos(Durations.toNanos(Durations.parse(idleTimeout)))
        egress.commonHttp.requestTimeout = java.time.Duration.ofNanos(Durations.toNanos(Durations.parse(requestTimeout)))
    }

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
        val proto = outgoingDependenciesProto {
            withInvalid(service = null, domain = null)
        }

        // expects
        val exception = assertThrows<NodeMetadataValidationException> { proto.toOutgoing(snapshotProperties()) }
        assertThat(exception.status.description)
            .isEqualTo("Define either 'service' or 'domain' as an outgoing dependency")
        assertThat(exception.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
    }

    @Test
    fun `should reject dependency with both service and domain fields defined`() {
        // given
        val proto = outgoingDependenciesProto {
            withInvalid(service = "service", domain = "http://domain")
        }

        // expects
        val exception = assertThrows<NodeMetadataValidationException> { proto.toOutgoing(snapshotProperties()) }
        assertThat(exception.status.description)
            .isEqualTo("Define either 'service' or 'domain' as an outgoing dependency")
        assertThat(exception.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
    }

    @Test
    fun `should reject dependency with unsupported protocol in domain field `() {
        // given
        val proto = outgoingDependenciesProto {
            withDomain(url = "ftp://domain")
        }

        // expects
        val exception = assertThrows<NodeMetadataValidationException> { proto.toOutgoing(snapshotProperties()) }
        assertThat(exception.status.description)
            .isEqualTo("Unsupported protocol for domain dependency for domain ftp://domain")
        assertThat(exception.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
    }

    @Test
    fun `should reject domain dependency with unsupported all services dependencies identifier`() {
        // given
        val proto = outgoingDependenciesProto {
            withDomain(url = "*")
        }
        val properties = snapshotProperties(allServicesDependenciesIdentifier = "*")

        // expects
        val exception = assertThrows<NodeMetadataValidationException> { proto.toOutgoing(properties) }
        assertThat(exception.status.description)
            .isEqualTo("Unsupported 'all serviceDependencies identifier' for domain dependency: *")
        assertThat(exception.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
    }

    @Test
    fun `should accept domain dependency`() {
        // given
        val proto = outgoingDependenciesProto {
            withDomain(url = "http://domain")
        }

        // when
        val outgoing = proto.toOutgoing(snapshotProperties())

        // expects
        val dependency = outgoing.domainDependencies.single()
        assertThat(dependency.domain).isEqualTo("http://domain")
    }

    @Test
    fun `should return correct host and default port for domain dependency`() {
        // given
        val proto = outgoingDependenciesProto {
            withDomain(url = "http://domain")
        }

        // when
        val outgoing = proto.toOutgoing(snapshotProperties())

        // expects
        val dependency = outgoing.domainDependencies.single()
        assertThat(dependency.getHost()).isEqualTo("domain")
        assertThat(dependency.getPort()).isEqualTo(80)
    }

    @Test
    fun `should return custom port for domain dependency if it was defined`() {
        // given
        val proto = outgoingDependenciesProto {
            withDomain(url = "http://domain:1234")
        }

        // when
        val outgoing = proto.toOutgoing(snapshotProperties())

        // expects
        val dependency = outgoing.domainDependencies.single()
        assertThat(dependency.getPort()).isEqualTo(1234)
    }

    @Test
    fun `should return correct names for domain dependency without port specified`() {
        // given
        val proto = outgoingDependenciesProto {
            withDomain(url = "http://domain.pl")
        }

        // when
        val outgoing = proto.toOutgoing(snapshotProperties())

        // expects
        val dependency = outgoing.domainDependencies.single()
        assertThat(dependency.getClusterName()).isEqualTo("domain_pl_80")
        assertThat(dependency.getRouteDomain()).isEqualTo("domain.pl")
    }

    @Test
    fun `should return correct names for domain dependency with port specified`() {
        // given
        val proto = outgoingDependenciesProto {
            withDomain(url = "http://domain.pl:80")
        }

        // when
        val outgoing = proto.toOutgoing(snapshotProperties())

        // expects
        val dependency = outgoing.domainDependencies.single()
        assertThat(dependency.getClusterName()).isEqualTo("domain_pl_80")
        assertThat(dependency.getRouteDomain()).isEqualTo("domain.pl:80")
    }

    @Test
    fun `should accept service dependency with redirect policy defined`() {
        // given
        val proto = outgoingDependenciesProto {
            withService(serviceName = "service-1", handleInternalRedirect = true)
        }

        // when
        val outgoing = proto.toOutgoing(snapshotProperties())

        // expect
        val dependency = outgoing.serviceDependencies.single()
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
    fun `should parse allServiceDependency with timeouts configuration`() {
        // given
        val proto = outgoingDependenciesProto {
            withService(serviceName = "*", idleTimeout = "10s", requestTimeout = "10s")
        }

        // when
        val outgoing = proto.toOutgoing(snapshotProperties(allServicesDependenciesIdentifier = "*"))

        // expects
        assertThat(outgoing.allServicesDependencies).isTrue()
        assertThat(outgoing.defaultServiceSettings).hasTimeouts(idleTimeout = "10s", requestTimeout = "10s")
        assertThat(outgoing.serviceDependencies).isEmpty()
    }

    @Test
    fun `should parse allServiceDependency and use requestTimeout from properties`() {
        // given
        val proto = outgoingDependenciesProto {
            withService(serviceName = "*", idleTimeout = "10s", requestTimeout = null)
        }
        val properties = snapshotProperties(allServicesDependenciesIdentifier = "*", requestTimeout = "5s")
        // when

        val outgoing = proto.toOutgoing(properties)

        // expects
        assertThat(outgoing.allServicesDependencies).isTrue()
        assertThat(outgoing.defaultServiceSettings).hasTimeouts(idleTimeout = "10s", requestTimeout = "5s")
        assertThat(outgoing.serviceDependencies).isEmpty()
    }

    @Test
    fun `should parse allServiceDependency and use idleTimeout from properties`() {
        // given
        val proto = outgoingDependenciesProto {
            withService(serviceName = "*", idleTimeout = null, requestTimeout = "10s")
        }
        val properties = snapshotProperties(allServicesDependenciesIdentifier = "*", idleTimeout = "5s")
        // when

        val outgoing = proto.toOutgoing(properties)

        // expects
        assertThat(outgoing.allServicesDependencies).isTrue()
        assertThat(outgoing.defaultServiceSettings).hasTimeouts(idleTimeout = "5s", requestTimeout = "10s")
        assertThat(outgoing.serviceDependencies).isEmpty()
    }

    @Test
    fun `should parse allServiceDependency and use timeouts from properties`() {
        // given
        val proto = outgoingDependenciesProto {
            withService(serviceName = "*", idleTimeout = null, requestTimeout = null)
        }
        val properties =
            snapshotProperties(allServicesDependenciesIdentifier = "*", idleTimeout = "5s", requestTimeout = "5s")
        // when

        val outgoing = proto.toOutgoing(properties)

        // expects
        assertThat(outgoing.allServicesDependencies).isTrue()
        assertThat(outgoing.defaultServiceSettings).hasTimeouts(idleTimeout = "5s", requestTimeout = "5s")
        assertThat(outgoing.serviceDependencies).isEmpty()
    }

    @Test
    fun `should parse service dependencies and for missing config use config defined in allServiceDependency`() {
        // given
        val proto = outgoingDependenciesProto {
            withService(serviceName = "*", idleTimeout = "10s", requestTimeout = "10s")
            withService(serviceName = "service-name-1", idleTimeout = "5s", requestTimeout = null)
            withService(serviceName = "service-name-2", idleTimeout = null, requestTimeout = "4s")
            withService(serviceName = "service-name-3", idleTimeout = null, requestTimeout = null)
        }
        val properties = snapshotProperties(allServicesDependenciesIdentifier = "*")
        // when

        val outgoing = proto.toOutgoing(properties)

        // expects
        assertThat(outgoing.allServicesDependencies).isTrue()
        assertThat(outgoing.defaultServiceSettings).hasTimeouts(idleTimeout = "10s", requestTimeout = "10s")

        outgoing.serviceDependencies.assertServiceDependency("service-name-1")
            .hasTimeouts(idleTimeout = "5s", requestTimeout = "10s")
        outgoing.serviceDependencies.assertServiceDependency("service-name-2")
            .hasTimeouts(idleTimeout = "10s", requestTimeout = "4s")
        outgoing.serviceDependencies.assertServiceDependency("service-name-3")
            .hasTimeouts(idleTimeout = "10s", requestTimeout = "10s")
    }

    @Test
    fun `should parse service dependencies and for missing configs use config defined in properties when allServiceDependency isn't defined`() {
        // given
        val proto = outgoingDependenciesProto {
            withService(serviceName = "service-name-1", idleTimeout = "5s", requestTimeout = null)
            withService(serviceName = "service-name-2", idleTimeout = null, requestTimeout = "4s")
            withService(serviceName = "service-name-3", idleTimeout = null, requestTimeout = null)
        }
        val properties = snapshotProperties(idleTimeout = "12s", requestTimeout = "12s")
        // when

        val outgoing = proto.toOutgoing(properties)

        // expects
        assertThat(outgoing.allServicesDependencies).isFalse()
        assertThat(outgoing.defaultServiceSettings).hasTimeouts(idleTimeout = "12s", requestTimeout = "12s")

        outgoing.serviceDependencies.assertServiceDependency("service-name-1")
            .hasTimeouts(idleTimeout = "5s", requestTimeout = "12s")
        outgoing.serviceDependencies.assertServiceDependency("service-name-2")
            .hasTimeouts(idleTimeout = "12s", requestTimeout = "4s")
        outgoing.serviceDependencies.assertServiceDependency("service-name-3")
            .hasTimeouts(idleTimeout = "12s", requestTimeout = "12s")
    }

    @Test
    fun `should parse domain dependencies and for missing config use config defined in properties even if allServiceDependency is defined`() {
        // given
        val proto = outgoingDependenciesProto {
            withService(serviceName = "*", idleTimeout = "10s", requestTimeout = "10s")
            withDomain(url = "http://domain-name-1", idleTimeout = "5s", requestTimeout = null)
            withDomain(url = "http://domain-name-2", idleTimeout = null, requestTimeout = "4s")
            withDomain(url = "http://domain-name-3", idleTimeout = null, requestTimeout = null)
        }
        val properties = snapshotProperties(idleTimeout = "12s", requestTimeout = "12s")
        // when

        val outgoing = proto.toOutgoing(properties)

        // expects
        assertThat(outgoing.allServicesDependencies).isTrue()
        assertThat(outgoing.defaultServiceSettings).hasTimeouts(idleTimeout = "10s", requestTimeout = "10s")

        outgoing.domainDependencies.assertDomainDependency("http://domain-name-1")
            .hasTimeouts(idleTimeout = "5s", requestTimeout = "12s")
        outgoing.domainDependencies.assertDomainDependency("http://domain-name-2")
            .hasTimeouts(idleTimeout = "12s", requestTimeout = "4s")
        outgoing.domainDependencies.assertDomainDependency("http://domain-name-3")
            .hasTimeouts(idleTimeout = "12s", requestTimeout = "12s")
    }

    @Test
    fun `should throw exception when there are multiple allServiceDependency`() {
        // given
        val proto = outgoingDependenciesProto {
            withServices(serviceDependencies = listOf("*", "*", "a"))
        }

        // expects
        val exception = assertThrows<NodeMetadataValidationException> {
            proto.toOutgoing(snapshotProperties(allServicesDependenciesIdentifier = "*"))
        }
        assertThat(exception.status.description)
            .isEqualTo("Define at most one 'all serviceDependencies identifier' as an service dependency")
        assertThat(exception.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
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
    fun `should reject configuration with number timeout format`() {
        // given
        val proto = Value.newBuilder().setNumberValue(10.0).build()

        // when
        val exception = assertThrows<NodeMetadataValidationException> { proto.toDuration() }

        // then
        assertThat(exception.status.description).isEqualTo(
            "Timeout definition has number format" +
                " but should be in string format and ends with 's'"
        )
        assertThat(exception.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
    }

    @Test
    fun `should reject configuration with incorrect string timeout format`() {
        // given
        val proto = Value.newBuilder().setStringValue("20").build()

        // when
        val exception = assertThrows<NodeMetadataValidationException> { proto.toDuration() }

        // then
        assertThat(exception.status.description).isEqualTo(
            "Timeout definition has incorrect format: " +
                "Invalid duration string: 20"
        )
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

    @ParameterizedTest
    @MethodSource("validStatusCodeFilterData")
    fun `should set statusCodeFilter for accessLogFilter`(input: String, op: ComparisonFilter.Op, code: Int) {
        // given
        val proto = accessLogFilterProto(statusCodeFilter = input)

        // when
        val statusCodeFilterSettings = proto.structValue?.fieldsMap?.get("status_code_filter").toStatusCodeFilter(
            AccessLogFilterFactory()
        )

        // expects
        assertThat(statusCodeFilterSettings?.comparisonCode).isEqualTo(code)
        assertThat(statusCodeFilterSettings?.comparisonOperator).isEqualTo(op)
    }

    @ParameterizedTest
    @MethodSource("invalidStatusCodeFilterData")
    fun `should throw exception for invalid status code filter data`(input: String) {
        // given
        val proto = accessLogFilterProto(statusCodeFilter = input)

        // expects
        val exception = assertThrows<NodeMetadataValidationException> {
            proto.structValue?.fieldsMap?.get("status_code_filter").toStatusCodeFilter(
                AccessLogFilterFactory()
            )
        }
        assertThat(exception.status.description)
            .isEqualTo("Invalid access log status code filter. Expected OPERATOR:STATUS_CODE")
        assertThat(exception.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
    }

    @Test
    fun `should throw exception for null value status code filter data`() {
        // given
        val proto = accessLogFilterProto(statusCodeFilter = null)

        // expects
        val exception = assertThrows<NodeMetadataValidationException> {
            proto.structValue?.fieldsMap?.get("status_code_filter").toStatusCodeFilter(
                AccessLogFilterFactory()
            )
        }
        assertThat(exception.status.description)
            .isEqualTo("Invalid access log status code filter. Expected OPERATOR:STATUS_CODE")
        assertThat(exception.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
    }

    fun ObjectAssert<DependencySettings>.hasTimeouts(idleTimeout: String, requestTimeout: String): ObjectAssert<DependencySettings> {
        this.extracting { it.timeoutPolicy }.isEqualTo(Outgoing.TimeoutPolicy(
            idleTimeout = Durations.parse(idleTimeout),
            requestTimeout = Durations.parse(requestTimeout)
        ))
        return this
    }

    fun List<ServiceDependency>.assertServiceDependency(name: String): ObjectAssert<DependencySettings> {
        val list = this.filter { it.service == name }
        assertThat(list).hasSize(1)
        val single = list.single().settings
        return assertThat(single)
    }

    fun List<DomainDependency>.assertDomainDependency(name: String): ObjectAssert<DependencySettings> {
        val list = this.filter { it.domain == name }
        assertThat(list).hasSize(1)
        val single = list.single().settings
        return assertThat(single)
    }
}
