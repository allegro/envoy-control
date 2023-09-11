package pl.allegro.tech.servicemesh.envoycontrol.groups

import com.google.protobuf.ListValue
import com.google.protobuf.NullValue
import com.google.protobuf.Struct
import com.google.protobuf.Value
import com.google.protobuf.util.Durations
import io.envoyproxy.envoy.config.accesslog.v3.ComparisonFilter
import io.grpc.Status
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.ObjectAssert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.JwtFilterProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.OAuthProvider
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotProperties
import java.net.URI
import java.time.Duration
import java.util.function.Consumer

@Suppress("LargeClass")
class NodeMetadataTest {

    companion object {
        @JvmStatic
        fun validComparisonFilterData() = listOf(
            arguments("Le:123", ComparisonFilter.Op.LE, 123),
            arguments("EQ:400", ComparisonFilter.Op.EQ, 400),
            arguments("gE:324", ComparisonFilter.Op.GE, 324),
            arguments("LE:200", ComparisonFilter.Op.LE, 200)
        )

        @JvmStatic
        fun errorMessages() = listOf(
            arguments("status_code_filter", "Invalid access log comparison filter. Expected OPERATOR:VALUE"),
            arguments("duration_code", "Invalid access log comparison filter. Expected OPERATOR:VALUE")
        )

        @JvmStatic
        fun invalidComparisonFilterData() = listOf(
            arguments("LT:123"),
            arguments("equal:400"),
            arguments("eq:24"),
            arguments("GT:200"),
            arguments("testeq:400test"),
            arguments("")
        )

        @JvmStatic
        fun invalidPathMatchingTypeCombinations() = listOf(
            arguments("/path", "/prefix", null),
            arguments("/path", null, "/regex"),
            arguments(null, "/prefix", "/regex"),
            arguments("/path", "/prefix", "/regex")
        )

        @JvmStatic
        fun parsingCustomData() = listOf(
            arguments("bool", Value.newBuilder().setBoolValue(true).build(), true),
            arguments("string", Value.newBuilder().setStringValue("string").build(), "string"),
            arguments("number", Value.newBuilder().setNumberValue(1.0).build(), 1.0),
            arguments("not_set", Value.newBuilder().build(), null),
            arguments("null", Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build(), null),
            arguments(
                "list", Value.newBuilder().setListValue(
                    ListValue.newBuilder()
                        .addValues(Value.newBuilder().setBoolValue(true).build()).build()
                )
                    .build(), listOf(true)
            ),
            arguments(
                "struct", Value.newBuilder().setStructValue(
                    Struct.newBuilder()
                        .putFields("string", Value.newBuilder().setBoolValue(true).build())
                        .build()
                ).build(), mapOf("string" to true)
            )
        )

        @JvmStatic
        fun parsingNotStructInCustomData() = listOf(
            arguments(Value.newBuilder().setBoolValue(true).build(), true),
            arguments(Value.newBuilder().setStringValue("string").build(), "string"),
            arguments(Value.newBuilder().setNumberValue(1.0).build(), 1.0),
            arguments(Value.newBuilder().build(), null),
            arguments(Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build(), null),
            arguments(
                Value.newBuilder().setListValue(
                    ListValue.newBuilder()
                        .addValues(Value.newBuilder().setBoolValue(true).build()).build()
                )
                    .build(), listOf(true)
            )
        )
    }

    private fun snapshotProperties(
        allServicesDependenciesIdentifier: String = "*",
        handleInternalRedirect: Boolean = false,
        idleTimeout: String = "120s",
        connectionIdleTimeout: String = "120s",
        requestTimeout: String = "120s"
    ) = SnapshotProperties().apply {
        outgoingPermissions.allServicesDependencies.identifier = allServicesDependenciesIdentifier
        egress.handleInternalRedirect = handleInternalRedirect
        egress.commonHttp.idleTimeout = Duration.ofNanos(Durations.toNanos(Durations.parse(idleTimeout)))
        egress.commonHttp.connectionIdleTimeout =
            Duration.ofNanos(Durations.toNanos(Durations.parse(connectionIdleTimeout)))
        egress.commonHttp.requestTimeout = Duration.ofNanos(Durations.toNanos(Durations.parse(requestTimeout)))
    }

    @Test
    fun `should reject endpoint with both path and pathPrefix defined`() {
        // given
        val proto = incomingEndpointProto(path = "/path", pathPrefix = "/prefix")

        // expects
        val exception = assertThrows<NodeMetadataValidationException> { proto.toIncomingEndpoint(snapshotProperties()) }
        assertThat(exception.status.description).isEqualTo("Precisely one of 'path', 'pathPrefix' or 'pathRegex' field is allowed")
        assertThat(exception.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
    }

    @ParameterizedTest
    @MethodSource("invalidPathMatchingTypeCombinations")
    fun `should reject endpoint with invalid combination of path, pathPrefix and pathRegex`(
        path: String?,
        pathPrefix: String?,
        pathRegex: String?
    ) {
        // given
        val proto = incomingEndpointProto(path = path, pathPrefix = pathPrefix, pathRegex = pathRegex)

        // expects
        val exception = assertThrows<NodeMetadataValidationException> { proto.toIncomingEndpoint(snapshotProperties()) }
        assertThat(exception.status.description).isEqualTo("Precisely one of 'path', 'pathPrefix' or 'pathRegex' field is allowed")
        assertThat(exception.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
    }

    @Test
    fun `should reject endpoint with no path or pathPrefix or pathRegex defined`() {
        // given
        val proto = incomingEndpointProto(path = null, pathPrefix = null, pathRegex = null)

        // expects
        val exception = assertThrows<NodeMetadataValidationException> { proto.toIncomingEndpoint(snapshotProperties()) }
        assertThat(exception.status.description).isEqualTo("One of 'path', 'pathPrefix' or 'pathRegex' field is required")
        assertThat(exception.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
    }

    @Test
    fun `should create group with oauth configuration`() {
        // given
        val snapshotProperties = createJwtSnapshotProperties()
        val oauth = OAuthTestDependencies("oauth2-mock", "offline", "strict")
        val proto = incomingEndpointProto(pathPrefix = "/prefix", includeNullFields = true, oauth = oauth)

        // when
        val result = proto.toIncomingEndpoint(snapshotProperties)

        // then
        assertThat(result.oauth?.provider).isEqualTo(oauth.provider)
        assertThat(result.oauth?.verification).isEqualTo(OAuth.Verification.OFFLINE)
        assertThat(result.oauth?.policy).isEqualTo(OAuth.Policy.STRICT)
    }

    @Test
    fun `should reject endpoint with invalid oauth provider`() {
        // given
        val oauthProvider = "oauth2-mock-invalid"
        val snapshotProperties = createJwtSnapshotProperties()
        val oauth = OAuthTestDependencies(oauthProvider, "offline", "strict")
        val proto = incomingEndpointProto(pathPrefix = "/prefix", includeNullFields = true, oauth = oauth)

        // when
        val exception = assertThrows<NodeMetadataValidationException> { proto.toIncomingEndpoint(snapshotProperties) }

        // then
        assertThat(exception.status.description).isEqualTo("Invalid OAuth provider value: $oauthProvider")
        assertThat(exception.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
    }

    @Test
    fun `should reject endpoint without oauth provider`() {
        // given
        val snapshotProperties = createJwtSnapshotProperties()
        val oauth = OAuthTestDependencies(null, "offline", "strict")
        val proto = incomingEndpointProto(pathPrefix = "/prefix", includeNullFields = true, oauth = oauth)

        // when
        val exception = assertThrows<NodeMetadataValidationException> { proto.toIncomingEndpoint(snapshotProperties) }

        // then
        assertThat(exception.status.description).isEqualTo("OAuth provider value cannot be null")
        assertThat(exception.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
    }

    @Test
    fun `should reject endpoint with oauth verification different than offline`() {
        // given
        val oauthVerification = "online"
        val snapshotProperties = createJwtSnapshotProperties()
        val oauth = OAuthTestDependencies("oauth2-mock", oauthVerification, "strict")
        val proto = incomingEndpointProto(pathPrefix = "/prefix", includeNullFields = true, oauth = oauth)

        // when
        val exception = assertThrows<NodeMetadataValidationException> { proto.toIncomingEndpoint(snapshotProperties) }

        // then
        assertThat(exception.status.description).isEqualTo("Invalid OAuth verification value: $oauthVerification")
        assertThat(exception.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
    }

    @Test
    fun `should reject endpoint with invalid oauth policy`() {
        // given
        val oauthPolicy = "custom"
        val snapshotProperties = createJwtSnapshotProperties()
        val oauth = OAuthTestDependencies("oauth2-mock", "offline", oauthPolicy)
        val proto = incomingEndpointProto(pathPrefix = "/prefix", includeNullFields = true, oauth = oauth)

        // when
        val exception = assertThrows<NodeMetadataValidationException> { proto.toIncomingEndpoint(snapshotProperties) }

        // then
        assertThat(exception.status.description).isEqualTo("Invalid OAuth policy value: $oauthPolicy")
        assertThat(exception.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
    }

    @Test
    fun `should accept endpoint with both path and pathPrefix defined but prefix is null`() {
        // given
        val proto = incomingEndpointProto(path = "/path", pathPrefix = null, includeNullFields = true)

        // when
        val result = proto.toIncomingEndpoint(snapshotProperties())

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
        val result = proto.toIncomingEndpoint(snapshotProperties())

        // then
        // no exception thrown
        assertThat(result.path).isEqualTo("/prefix")
        assertThat(result.pathMatchingType).isEqualTo(PathMatchingType.PATH_PREFIX)
    }

    @Test
    fun `should accept endpoint with both path and pathRegex defined but pathRegex is null`() {
        // given
        val proto = incomingEndpointProto(path = "/path", pathRegex = null, includeNullFields = true)

        // when
        val result = proto.toIncomingEndpoint(snapshotProperties())

        // then
        // no exception thrown
        assertThat(result.path).isEqualTo("/path")
        assertThat(result.pathMatchingType).isEqualTo(PathMatchingType.PATH)
    }

    @Test
    fun `should accept endpoint with both path and pathRegex defined but path is null`() {
        // given
        val proto = incomingEndpointProto(path = null, pathRegex = "/regex", includeNullFields = true)

        // when
        val result = proto.toIncomingEndpoint(snapshotProperties())

        // then
        // no exception thrown
        assertThat(result.path).isEqualTo("/regex")
        assertThat(result.pathMatchingType).isEqualTo(PathMatchingType.PATH_REGEX)
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
            .isEqualTo("Define one of: 'service', 'domain' or 'domainPattern' as an outgoing dependency")
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
            .isEqualTo("Define one of: 'service', 'domain' or 'domainPattern' as an outgoing dependency")
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
        val dependency = outgoing.getDomainDependencies().single()
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
        val dependency = outgoing.getDomainDependencies().single()
        assertThat(dependency.getHost()).isEqualTo("domain")
        assertThat(dependency.getPort()).isEqualTo(80)
    }

    @Test
    fun `should return correct host and default port for https domain dependency`() {
        // given
        val proto = outgoingDependenciesProto {
            withDomain(url = "https://domain")
        }

        // when
        val outgoing = proto.toOutgoing(snapshotProperties())

        // expects
        val dependency = outgoing.getDomainDependencies().single()
        assertThat(dependency.getHost()).isEqualTo("domain")
        assertThat(dependency.getPort()).isEqualTo(443)
    }

    @Test
    fun `should return retry policy`() {
        // given
        val givenRetryPolicy = RetryPolicyInput(
            retryOn = listOf("givenRetryOn"),
            hostSelectionRetryMaxAttempts = 1,
            numberRetries = 2,
            perTryTimeoutMs = 3,
            retryableHeaders = listOf("givenTestHeader"),
            retryableStatusCodes = listOf(504),
            retryBackOff = RetryBackOffInput(
                baseInterval = "7s",
                maxInterval = "8s"
            ),
            retryHostPredicate = listOf(RetryHostPredicateInput(name = "previous_hosts")),
            methods = setOf("GET", "POST", "PUT")
        )
        val expectedRetryPolicy = RetryPolicy(
            retryOn = listOf("givenRetryOn"),
            hostSelectionRetryMaxAttempts = 1,
            numberRetries = 2,
            perTryTimeoutMs = 3,
            retryableHeaders = listOf("givenTestHeader"),
            retryableStatusCodes = listOf(504),
            retryBackOff = RetryBackOff(
                baseInterval = Durations.fromSeconds(7),
                maxInterval = Durations.fromSeconds(8)
            ),
            retryHostPredicate = listOf(RetryHostPredicate.PREVIOUS_HOSTS),
            methods = setOf("GET", "POST", "PUT"),
            rateLimitedRetryBackOff = RateLimitedRetryBackOff(
                listOf(ResetHeader("Retry-After", "SECONDS"))
            )
        )
        val proto = outgoingDependenciesProto {
            withService(
                serviceName = "givenServiceName",
                retryPolicy = givenRetryPolicy
            )
        }

        // when
        val outgoing = proto.toOutgoing(snapshotProperties())

        // expects
        val serviceDependency = outgoing.getServiceDependencies().single()
        assertThat(serviceDependency.settings.retryPolicy).isEqualTo(expectedRetryPolicy)
    }

    @Test
    fun `should return retry policy with defaults`() {
        // given
        val givenRetryPolicy = RetryPolicyInput(
            retryOn = listOf("givenRetryOn"),
            perTryTimeoutMs = 3,
            retryableHeaders = listOf("givenTestHeader"),
            retryableStatusCodes = listOf(504),
            methods = setOf("GET", "POST", "PUT")
        )
        val expectedRetryPolicy = RetryPolicy(
            retryOn = listOf("givenRetryOn"),
            hostSelectionRetryMaxAttempts = 3,
            numberRetries = 1,
            perTryTimeoutMs = 3,
            retryableHeaders = listOf("givenTestHeader"),
            retryableStatusCodes = listOf(504),
            retryBackOff = RetryBackOff(
                baseInterval = Durations.fromMillis(25),
                maxInterval = Durations.fromMillis(250)
            ),
            retryHostPredicate = listOf(RetryHostPredicate.PREVIOUS_HOSTS),
            methods = setOf("GET", "POST", "PUT"),
            rateLimitedRetryBackOff = RateLimitedRetryBackOff(
                listOf(ResetHeader("Retry-After", "SECONDS"))
            )
        )
        val proto = outgoingDependenciesProto {
            withService(
                serviceName = "givenServiceName",
                retryPolicy = givenRetryPolicy
            )
        }

        // when
        val outgoing = proto.toOutgoing(snapshotProperties())

        // expects
        val serviceDependency = outgoing.getServiceDependencies().single()
        assertThat(serviceDependency.settings.retryPolicy).usingRecursiveComparison().isEqualTo(expectedRetryPolicy)
    }

    @Test
    fun `should deduplicate domains dependencies based on url`() {
        // given
        val proto = outgoingDependenciesProto {
            withDomain(url = "http://domain", requestTimeout = "8s", idleTimeout = "8s")
            withDomain(url = "http://domain", requestTimeout = "10s", idleTimeout = "10s", connectionIdleTimeout = "5s")
            withDomain(url = "http://domain2")
        }

        // when
        val outgoing = proto.toOutgoing(snapshotProperties())

        // expects
        val dependencies = outgoing.getDomainDependencies()
        assertThat(dependencies).hasSize(2)
        assertThat(dependencies[0].getHost()).isEqualTo("domain")
        assertThat(dependencies[0].getPort()).isEqualTo(80)
        assertThat(dependencies[0].settings).hasTimeouts(
            idleTimeout = "10s",
            requestTimeout = "10s",
            connectionIdleTimeout = "5s"
        )
        assertThat(dependencies[1].getHost()).isEqualTo("domain2")
        assertThat(dependencies[1].getPort()).isEqualTo(80)
    }

    @Test
    fun `should deduplicate services dependencies based on serviceName`() {
        // given
        val proto = outgoingDependenciesProto {
            withService(serviceName = "service-1", requestTimeout = "8s", idleTimeout = "8s")
            withService(
                serviceName = "service-1",
                requestTimeout = "10s",
                idleTimeout = "10s",
                connectionIdleTimeout = "10s"
            )
            withService(serviceName = "service-2")
        }

        // when
        val outgoing = proto.toOutgoing(snapshotProperties())

        // expect
        val dependencies = outgoing.getServiceDependencies()
        assertThat(dependencies).hasSize(2)
        assertThat(dependencies[0].service).isEqualTo("service-1")
        assertThat(dependencies[0].settings).hasTimeouts(
            idleTimeout = "10s",
            requestTimeout = "10s",
            connectionIdleTimeout = "10s"
        )
        assertThat(dependencies[1].service).isEqualTo("service-2")
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
        val dependency = outgoing.getDomainDependencies().single()
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
        val dependency = outgoing.getDomainDependencies().single()
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
        val dependency = outgoing.getDomainDependencies().single()
        assertThat(dependency.getClusterName()).isEqualTo("domain_pl_80")
        assertThat(dependency.getRouteDomain()).isEqualTo("domain.pl:80")
    }

    @Test
    fun `should accept domain pattern dependency`() {
        // given
        val proto = outgoingDependenciesProto {
            withDomainPattern(pattern = "*.example.com")
        }

        // when
        val outgoing = proto.toOutgoing(snapshotProperties())

        // expects
        val dependency = outgoing.getDomainPatternDependencies().single()
        assertThat(dependency.domainPattern).isEqualTo("*.example.com")
    }

    @Test
    fun `should reject domain pattern dependency with schema`() {
        // given
        val proto = outgoingDependenciesProto {
            withDomainPattern(pattern = "http://example.com")
        }

        // expects
        val exception = assertThrows<NodeMetadataValidationException> { proto.toOutgoing(snapshotProperties()) }
        assertThat(exception.status.description).isEqualTo(
            "Unsupported format for domainPattern: domainPattern cannot contain a schema like http:// or https://"
        )
        assertThat(exception.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
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
        val dependency = outgoing.getServiceDependencies().single()
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
        val incoming = proto.structValue?.fieldsMap?.get("incoming").toIncoming(snapshotProperties())

        // expects
        assertThat(incoming.healthCheck.clusterName).isEqualTo("local_service_health_check")
        assertThat(incoming.healthCheck.path).isEqualTo("/status/ping")
        assertThat(incoming.healthCheck.hasCustomHealthCheck()).isTrue()
    }

    @Test
    fun `should parse allServiceDependency with timeouts configuration`() {
        // given
        val proto = outgoingDependenciesProto {
            withService(serviceName = "*", idleTimeout = "10s", requestTimeout = "10s", connectionIdleTimeout = "10s")
        }

        // when
        val outgoing = proto.toOutgoing(snapshotProperties(allServicesDependenciesIdentifier = "*"))

        // expects
        assertThat(outgoing.allServicesDependencies).isTrue()
        assertThat(outgoing.defaultServiceSettings).hasTimeouts(
            idleTimeout = "10s",
            requestTimeout = "10s",
            connectionIdleTimeout = "10s"
        )
        assertThat(outgoing.getServiceDependencies()).isEmpty()
    }

    @Test
    fun `should parse allServiceDependency and use requestTimeout from properties`() {
        // given
        val proto = outgoingDependenciesProto {
            withService(serviceName = "*", idleTimeout = "10s", requestTimeout = null, connectionIdleTimeout = "10s")
        }
        val properties = snapshotProperties(allServicesDependenciesIdentifier = "*", requestTimeout = "5s")
        // when

        val outgoing = proto.toOutgoing(properties)

        // expects
        assertThat(outgoing.allServicesDependencies).isTrue()
        assertThat(outgoing.defaultServiceSettings).hasTimeouts(
            idleTimeout = "10s",
            requestTimeout = "5s",
            connectionIdleTimeout = "10s"
        )
        assertThat(outgoing.getServiceDependencies()).isEmpty()
    }

    @Test
    fun `should parse allServiceDependency and use idleTimeout from properties`() {
        // given
        val proto = outgoingDependenciesProto {
            withService(serviceName = "*", idleTimeout = null, requestTimeout = "10s", connectionIdleTimeout = "10s")
        }
        val properties = snapshotProperties(allServicesDependenciesIdentifier = "*", idleTimeout = "5s")
        // when

        val outgoing = proto.toOutgoing(properties)

        // expects
        assertThat(outgoing.allServicesDependencies).isTrue()
        assertThat(outgoing.defaultServiceSettings).hasTimeouts(
            idleTimeout = "5s",
            requestTimeout = "10s",
            connectionIdleTimeout = "10s"
        )
        assertThat(outgoing.getServiceDependencies()).isEmpty()
    }

    @Test
    fun `should parse allServiceDependency and use connectionIdleTimeout from properties`() {
        // given
        val proto = outgoingDependenciesProto {
            withService(serviceName = "*", idleTimeout = "10s", requestTimeout = "10s", connectionIdleTimeout = null)
        }
        val properties = snapshotProperties(allServicesDependenciesIdentifier = "*", connectionIdleTimeout = "5s")
        // when

        val outgoing = proto.toOutgoing(properties)

        // expects
        assertThat(outgoing.allServicesDependencies).isTrue()
        assertThat(outgoing.defaultServiceSettings).hasTimeouts(
            idleTimeout = "10s",
            requestTimeout = "10s",
            connectionIdleTimeout = "5s"
        )
        assertThat(outgoing.getServiceDependencies()).isEmpty()
    }

    @Test
    fun `should parse allServiceDependency and use timeouts from properties`() {
        // given
        val proto = outgoingDependenciesProto {
            withService(serviceName = "*", idleTimeout = null, requestTimeout = null)
        }
        val properties =
            snapshotProperties(
                allServicesDependenciesIdentifier = "*",
                idleTimeout = "5s",
                requestTimeout = "5s",
                connectionIdleTimeout = "5s"
            )
        // when

        val outgoing = proto.toOutgoing(properties)

        // expects
        assertThat(outgoing.allServicesDependencies).isTrue()
        assertThat(outgoing.defaultServiceSettings).hasTimeouts(
            idleTimeout = "5s",
            requestTimeout = "5s",
            connectionIdleTimeout = "5s"
        )
        assertThat(outgoing.getServiceDependencies()).isEmpty()
    }

    @Test
    fun `should parse service dependencies and for missing config use config defined in allServiceDependency`() {
        // given
        val proto = outgoingDependenciesProto {
            withService(serviceName = "*", idleTimeout = "10s", requestTimeout = "10s", connectionIdleTimeout = "10s")
            withService(
                serviceName = "service-name-1",
                idleTimeout = "5s",
                requestTimeout = null,
                connectionIdleTimeout = null
            )
            withService(
                serviceName = "service-name-2",
                idleTimeout = null,
                requestTimeout = "4s",
                connectionIdleTimeout = null
            )
            withService(
                serviceName = "service-name-3",
                idleTimeout = null,
                requestTimeout = null,
                connectionIdleTimeout = "3s"
            )
            withService(
                serviceName = "service-name-4",
                idleTimeout = null,
                requestTimeout = null,
                connectionIdleTimeout = null
            )
        }
        val properties = snapshotProperties(allServicesDependenciesIdentifier = "*")
        // when

        val outgoing = proto.toOutgoing(properties)

        // expects
        assertThat(outgoing.allServicesDependencies).isTrue()
        assertThat(outgoing.defaultServiceSettings).hasTimeouts(
            idleTimeout = "10s",
            requestTimeout = "10s",
            connectionIdleTimeout = "10s"
        )

        outgoing.getServiceDependencies().assertServiceDependency("service-name-1")
            .hasTimeouts(idleTimeout = "5s", requestTimeout = "10s", connectionIdleTimeout = "10s")
        outgoing.getServiceDependencies().assertServiceDependency("service-name-2")
            .hasTimeouts(idleTimeout = "10s", requestTimeout = "4s", connectionIdleTimeout = "10s")
        outgoing.getServiceDependencies().assertServiceDependency("service-name-3")
            .hasTimeouts(idleTimeout = "10s", requestTimeout = "10s", connectionIdleTimeout = "3s")
        outgoing.getServiceDependencies().assertServiceDependency("service-name-4")
            .hasTimeouts(idleTimeout = "10s", requestTimeout = "10s", connectionIdleTimeout = "10s")
    }

    @Test
    fun `should parse service dependencies and for missing configs use config defined in properties when allServiceDependency isn't defined`() {
        // given
        val proto = outgoingDependenciesProto {
            withService(
                serviceName = "service-name-1",
                idleTimeout = "5s",
                requestTimeout = null,
                connectionIdleTimeout = null
            )
            withService(
                serviceName = "service-name-2",
                idleTimeout = null,
                requestTimeout = "4s",
                connectionIdleTimeout = null
            )
            withService(
                serviceName = "service-name-3",
                idleTimeout = null,
                requestTimeout = null,
                connectionIdleTimeout = "3s"
            )
            withService(
                serviceName = "service-name-4",
                idleTimeout = null,
                requestTimeout = null,
                connectionIdleTimeout = null
            )
        }
        val properties = snapshotProperties(idleTimeout = "12s", requestTimeout = "12s", connectionIdleTimeout = "12s")
        // when

        val outgoing = proto.toOutgoing(properties)

        // expects
        assertThat(outgoing.allServicesDependencies).isFalse()
        assertThat(outgoing.defaultServiceSettings).hasTimeouts(
            idleTimeout = "12s",
            requestTimeout = "12s",
            connectionIdleTimeout = "12s"
        )

        outgoing.getServiceDependencies().assertServiceDependency("service-name-1")
            .hasTimeouts(idleTimeout = "5s", requestTimeout = "12s", connectionIdleTimeout = "12s")
        outgoing.getServiceDependencies().assertServiceDependency("service-name-2")
            .hasTimeouts(idleTimeout = "12s", requestTimeout = "4s", connectionIdleTimeout = "12s")
        outgoing.getServiceDependencies().assertServiceDependency("service-name-3")
            .hasTimeouts(idleTimeout = "12s", requestTimeout = "12s", connectionIdleTimeout = "3s")
        outgoing.getServiceDependencies().assertServiceDependency("service-name-4")
            .hasTimeouts(idleTimeout = "12s", requestTimeout = "12s", connectionIdleTimeout = "12s")
    }

    @Test
    fun `should parse domain dependencies and for missing config use config defined in properties even if allServiceDependency is defined`() {
        // given
        val proto = outgoingDependenciesProto {
            withService(serviceName = "*", idleTimeout = "10s", requestTimeout = "10s", connectionIdleTimeout = "10s")
            withDomain(
                url = "http://domain-name-1",
                idleTimeout = "5s",
                requestTimeout = null,
                connectionIdleTimeout = null
            )
            withDomain(
                url = "http://domain-name-2",
                idleTimeout = null,
                requestTimeout = "4s",
                connectionIdleTimeout = null
            )
            withDomain(
                url = "http://domain-name-3",
                idleTimeout = null,
                requestTimeout = null,
                connectionIdleTimeout = "3s"
            )
            withDomain(
                url = "http://domain-name-4",
                idleTimeout = null,
                requestTimeout = null,
                connectionIdleTimeout = null
            )
        }
        val properties = snapshotProperties(idleTimeout = "12s", requestTimeout = "12s", connectionIdleTimeout = "12s")
        // when

        val outgoing = proto.toOutgoing(properties)

        // expects
        assertThat(outgoing.allServicesDependencies).isTrue()
        assertThat(outgoing.defaultServiceSettings).hasTimeouts(
            idleTimeout = "10s",
            requestTimeout = "10s",
            connectionIdleTimeout = "10s"
        )

        outgoing.getDomainDependencies().assertDomainDependency("http://domain-name-1")
            .hasTimeouts(idleTimeout = "5s", requestTimeout = "12s", connectionIdleTimeout = "12s")
        outgoing.getDomainDependencies().assertDomainDependency("http://domain-name-2")
            .hasTimeouts(idleTimeout = "12s", requestTimeout = "4s", connectionIdleTimeout = "12s")
        outgoing.getDomainDependencies().assertDomainDependency("http://domain-name-3")
            .hasTimeouts(idleTimeout = "12s", requestTimeout = "12s", connectionIdleTimeout = "3s")
        outgoing.getDomainDependencies().assertDomainDependency("http://domain-name-4")
            .hasTimeouts(idleTimeout = "12s", requestTimeout = "12s", connectionIdleTimeout = "12s")
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
        val incoming = proto.structValue?.fieldsMap?.get("incoming").toIncoming(snapshotProperties())

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
        val incoming = proto.structValue?.fieldsMap?.get("incoming").toIncoming(snapshotProperties())

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
    @MethodSource("validComparisonFilterData")
    fun `should set statusCodeFilter for accessLogFilter`(input: String, op: ComparisonFilter.Op, code: Int) {
        // given
        val proto = accessLogFilterProto(value = input, fieldName = "status_code_filter")

        // when
        val statusCodeFilterSettings = proto.structValue?.fieldsMap?.get("status_code_filter").toComparisonFilter()

        // expects
        assertThat(statusCodeFilterSettings?.comparisonCode).isEqualTo(code)
        assertThat(statusCodeFilterSettings?.comparisonOperator).isEqualTo(op)
    }

    @ParameterizedTest
    @MethodSource("invalidComparisonFilterData")
    fun `should throw exception for invalid status code filter data`(input: String) {
        // given
        val proto = accessLogFilterProto(value = input, fieldName = "status_code_filter")

        // expects
        val exception = assertThrows<NodeMetadataValidationException> {
            proto.structValue?.fieldsMap?.get("status_code_filter").toComparisonFilter()
        }
        assertThat(exception.status.description)
            .isEqualTo("Invalid access log comparison filter. Expected OPERATOR:VALUE")
        assertThat(exception.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
    }

    @ParameterizedTest
    @MethodSource("errorMessages")
    fun `should throw exception for null value comparison filter data`(filter: String, errorMessage: String) {
        // given
        val proto = accessLogFilterProto(value = null, fieldName = filter)

        // expects
        val exception = assertThrows<NodeMetadataValidationException> {
            proto.structValue?.fieldsMap?.get(filter).toComparisonFilter()
        }
        assertThat(exception.status.description)
            .isEqualTo(errorMessage)
        assertThat(exception.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
    }

    @ParameterizedTest
    @MethodSource("validComparisonFilterData")
    fun `should set duration filter for accessLogFilter`(input: String, op: ComparisonFilter.Op, code: Int) {
        // given
        val proto = accessLogFilterProto(value = input, fieldName = "duration_filter")

        // when
        val durationFilterSettings = proto.structValue?.fieldsMap?.get("duration_filter").toComparisonFilter()

        // expects
        assertThat(durationFilterSettings?.comparisonCode).isEqualTo(code)
        assertThat(durationFilterSettings?.comparisonOperator).isEqualTo(op)
    }

    @ParameterizedTest
    @MethodSource("invalidComparisonFilterData")
    fun `should throw exception for invalid duration filter data`(input: String) {
        // given
        val proto = accessLogFilterProto(value = input, fieldName = "duration_filter")

        // expects
        val exception = assertThrows<NodeMetadataValidationException> {
            proto.structValue?.fieldsMap?.get("duration_filter").toComparisonFilter()
        }
        assertThat(exception.status.description)
            .isEqualTo("Invalid access log comparison filter. Expected OPERATOR:VALUE")
        assertThat(exception.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
    }

    @Test
    fun `should throw exception for null value header filter data`() {
        // given
        val proto = accessLogFilterProto(value = null, fieldName = "header_filter")

        // expects
        val exception = assertThrows<NodeMetadataValidationException> {
            proto.structValue?.fieldsMap?.get("header_filter").toHeaderFilter()
        }
        assertThat(exception.status.description)
            .isEqualTo("Invalid access log header filter. Expected HEADER_NAME:REGEX")
        assertThat(exception.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
    }

    @Test
    fun `should set header filter for accessLogFilter`() {
        // given
        val proto = accessLogFilterProto(value = "test:^((.+):(.+))$", fieldName = "header_filter")

        // when
        val headerFilterSettings = proto.structValue?.fieldsMap?.get("header_filter").toHeaderFilter()

        // expects
        assertThat(headerFilterSettings?.headerName).isEqualTo("test")
        assertThat(headerFilterSettings?.regex).isEqualTo("^((.+):(.+))\$")
    }

    @Test
    fun `should throw exception for invalid header filter data`() {
        // given
        val proto = accessLogFilterProto(value = "test;test", fieldName = "header_filter")

        // expects
        val exception = assertThrows<NodeMetadataValidationException> {
            proto.structValue?.fieldsMap?.get("header_filter").toHeaderFilter()
        }
        assertThat(exception.status.description)
            .isEqualTo("Invalid access log header filter. Expected HEADER_NAME:REGEX")
        assertThat(exception.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
    }

    @Test
    fun `should set response flag filter for accessLogFilter`() {
        // given
        val availableFlags = listOf(
            "UH", "UF", "UO", "NR", "URX", "NC", "DT", "DC", "LH", "UT", "LR", "UR",
            "UC", "DI", "FI", "RL", "UAEX", "RLSE", "IH", "SI", "DPE", "UPE", "UMSDR",
            "OM", "DF"
        )
        val proto = accessLogFilterProto(value = availableFlags.joinToString(","), fieldName = "response_flag_filter")

        // when
        val responseFlags = proto.structValue?.fieldsMap?.get("response_flag_filter").toResponseFlagFilter()

        // expects
        assertThat(responseFlags).isEqualTo(availableFlags)
    }

    @Test
    fun `should throw exception for invalid response flag filter data`() {
        // given
        val availableFlagsAndInvalid = listOf(
            "UH", "UF", "UO", "NR", "URX", "NC", "DT", "DC", "LH", "UT", "LR", "UR",
            "UC", "DI", "FI", "RL", "UAEX", "RLSE", "IH", "SI", "DPE", "UPE", "UMSDR",
            "OM", "DF", "invalid"
        )
        val proto =
            accessLogFilterProto(value = availableFlagsAndInvalid.joinToString(","), fieldName = "response_flag_filter")

        // expects
        val exception = assertThrows<NodeMetadataValidationException> {
            proto.structValue?.fieldsMap?.get("response_flag_filter").toResponseFlagFilter()
        }
        assertThat(exception.status.description)
            .isEqualTo("Invalid access log response flag filter. Expected valid values separated by comma")
        assertThat(exception.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
    }

    @Test
    fun `should set not health check filter for accessLogFilter`() {
        // given
        val proto = accessLogBooleanFilterProto(value = true, fieldName = "not_health_check_filter")

        // when
        val value = proto.structValue?.fieldsMap?.get("not_health_check_filter")?.boolValue

        // expects
        assertThat(value).isEqualTo(true)
    }

    @Test
    fun `should throw exception for null value response flag filter data`() {
        // given
        val proto = accessLogFilterProto(value = null, fieldName = "response_flag_filter")

        // expects
        val exception = assertThrows<NodeMetadataValidationException> {
            proto.structValue?.fieldsMap?.get("response_flag_filter").toResponseFlagFilter()
        }
        assertThat(exception.status.description)
            .isEqualTo("Invalid access log response flag filter. Expected valid values separated by comma")
        assertThat(exception.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
    }

    @Test
    fun `should use default routing policy`() {
        // given
        val proto = outgoingDependenciesProto {
            withService("lorem")
        }

        // when
        val outgoing = proto.toOutgoing(snapshotProperties())

        // expects
        val loremDependency = outgoing.getServiceDependencies().single()
        assertThat(loremDependency.service).isEqualTo("lorem")
        assertThat(loremDependency.settings.routingPolicy.autoServiceTag).isFalse
    }

    @Test
    fun `should use global and overriden routing policy`() {
        // given
        val proto = outgoingDependenciesProto {
            routingPolicy = RoutingPolicyInput(
                autoServiceTag = true,
                serviceTagPreference = listOf("preferredGlobalTag", "fallbackGlobalTag"),
                fallbackToAnyInstance = true
            )
            withService("lorem")
            withService("ipsum", routingPolicy = RoutingPolicyInput(autoServiceTag = false))
            withService("dolom", routingPolicy = RoutingPolicyInput(fallbackToAnyInstance = false))
            withService(
                "est", routingPolicy = RoutingPolicyInput(serviceTagPreference = listOf("estTag"))
            )
        }

        // when
        val outgoing = proto.toOutgoing(snapshotProperties())

        // expects
        val dependencies = outgoing.getServiceDependencies()
        assertThat(dependencies).hasSize(4)
        val loremDependency = dependencies[0]
        assertThat(loremDependency.service).isEqualTo("lorem")
        assertThat(loremDependency.settings.routingPolicy).satisfies(Consumer { policy ->
            assertThat(policy.autoServiceTag).isTrue
            assertThat(policy.serviceTagPreference).isEqualTo(listOf("preferredGlobalTag", "fallbackGlobalTag"))
            assertThat(policy.fallbackToAnyInstance).isTrue
        })
        val ipsumDependency = dependencies[1]
        assertThat(ipsumDependency.service).isEqualTo("ipsum")
        assertThat(ipsumDependency.settings.routingPolicy).satisfies(Consumer { policy ->
            assertThat(policy.autoServiceTag).isFalse
        })
        val dolomDependency = dependencies[2]
        assertThat(dolomDependency.service).isEqualTo("dolom")
        assertThat(dolomDependency.settings.routingPolicy).satisfies(Consumer { policy ->
            assertThat(policy.autoServiceTag).isTrue
            assertThat(policy.serviceTagPreference).isEqualTo(listOf("preferredGlobalTag", "fallbackGlobalTag"))
            assertThat(policy.fallbackToAnyInstance).isFalse
        })
        val estDependency = dependencies[3]
        assertThat(estDependency.service).isEqualTo("est")
        assertThat(estDependency.settings.routingPolicy).satisfies(Consumer { policy ->
            assertThat(policy.autoServiceTag).isTrue
            assertThat(policy.serviceTagPreference).isEqualTo(listOf("estTag"))
            assertThat(policy.fallbackToAnyInstance).isTrue
        })
    }

    @ParameterizedTest
    @MethodSource("parsingNotStructInCustomData")
    fun `should return empty custom data if is not a struct`(value: Value) {
        // when
        val customData = value.toCustomData()

        // then
        assertThat(customData).isEmpty()
    }

    @ParameterizedTest
    @MethodSource("parsingCustomData")
    fun `should parse custom data if it is a struct with value`(name: String, field: Value, expected: Any?) {
        // given
        val value = Value.newBuilder()
            .setStructValue(
                Struct.newBuilder()
                    .putFields(name, field)
                    .build()
            )
            .build()

        // when
        val customData = value.toCustomData()

        // then
        assertThat(customData).isEqualTo(mapOf(name to expected))
    }

    @Test
    fun `should parse custom data if is a struct`() {
        // given
        val value = Value.newBuilder().setStructValue(
            Struct.newBuilder()
                .putFields("abc", Value.newBuilder().setBoolValue(true).build())
                .build()
        ).build()

        // when
        val customData = value.toCustomData()

        // then
        assertThat(customData).isEqualTo(mapOf("abc" to true))
    }

    fun ObjectAssert<DependencySettings>.hasTimeouts(
        idleTimeout: String,
        connectionIdleTimeout: String,
        requestTimeout: String
    ): ObjectAssert<DependencySettings> {
        this.extracting { it.timeoutPolicy }.isEqualTo(
            Outgoing.TimeoutPolicy(
                idleTimeout = Durations.parse(idleTimeout),
                connectionIdleTimeout = Durations.parse(connectionIdleTimeout),
                requestTimeout = Durations.parse(requestTimeout)
            )
        )
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

    private fun createJwtSnapshotProperties(): SnapshotProperties {
        val snapshotProperties = SnapshotProperties()
        val jwtFilterProperties = JwtFilterProperties()
        val oauthProviders = mapOf(
            "oauth2-mock" to
                OAuthProvider(
                    jwksUri = URI.create("http://localhost:8080/jwks-address/"),
                    clusterName = "oauth"
                )
        )
        jwtFilterProperties.providers = oauthProviders
        snapshotProperties.jwt = jwtFilterProperties

        return snapshotProperties
    }
}
