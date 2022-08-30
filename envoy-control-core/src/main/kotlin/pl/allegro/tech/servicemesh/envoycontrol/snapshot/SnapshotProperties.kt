@file:Suppress("MagicNumber")

package pl.allegro.tech.servicemesh.envoycontrol.snapshot

import io.envoyproxy.envoy.config.cluster.v3.Cluster
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.TlsParameters
import pl.allegro.tech.servicemesh.envoycontrol.groups.OAuth
import pl.allegro.tech.servicemesh.envoycontrol.groups.PathMatchingType
import pl.allegro.tech.servicemesh.envoycontrol.groups.RetryHostPredicate
import java.net.URI
import java.time.Duration

class SnapshotProperties {
    var routes = RoutesProperties()
    var localService = LocalServiceProperties()
    var egress = EgressProperties()
    var ingress = IngressProperties()
    var incomingPermissions = IncomingPermissionsProperties()
    var outgoingPermissions = OutgoingPermissionsProperties()
    var loadBalancing = LoadBalancingProperties()
    var clusterOutlierDetection = ClusterOutlierDetectionProperties()
    var routing = RoutingProperties()
    var xdsClusterName = "envoy-control-xds"
    var edsConnectionTimeout: Duration = Duration.ofSeconds(2)
    var stateSampleDuration: Duration = Duration.ofSeconds(1)
    var staticClusterConnectionTimeout: Duration = Duration.ofSeconds(2)
    var trustedCaFile = "/etc/ssl/certs/ca-certificates.crt"
    var dynamicListeners = ListenersFactoryProperties()
    var enabledCommunicationModes = EnabledCommunicationModes()
    var shouldSendMissingEndpoints = false
    var metrics: MetricsProperties = MetricsProperties()
    var dynamicForwardProxy = DynamicForwardProxyProperties()
    var jwt = JwtFilterProperties()
    var requireServiceName = false
    var rateLimit = RateLimitProperties()
    var retryPolicy = RetryPolicyProperties()
    var shouldAuditGlobalSnapshot = false
    var tracing = false
}

class MetricsProperties {
    var cacheSetSnapshot = false
}

class ListenersFactoryProperties {
    var enabled = true
    var httpFilters = HttpFiltersProperties()
    var localReplyMapper = LocalReplyMapperProperties()
}

class HttpFiltersProperties {
    var accessLog = AccessLogProperties()
    var ingressXffNumTrustedHops = 1
}

class AccessLogProperties {
    var timeFormat = "%START_TIME(%FT%T.%3fZ)%"
    var messageFormat = "%PROTOCOL% %REQ(:METHOD)% %REQ(:authority)% %REQ(:PATH)% " +
            "%DOWNSTREAM_REMOTE_ADDRESS% -> %UPSTREAM_HOST%"
    var level = "TRACE"
    var logger = "envoy.AccessLog"
    var customFields = mapOf<String, String>()
}

class OutgoingPermissionsProperties {
    var enabled = false
    var allServicesDependencies = AllServicesDependenciesProperties()
    var servicesAllowedToUseWildcard: MutableSet<String> = mutableSetOf()
}

class AllServicesDependenciesProperties {
    var identifier = "*"
    var notIncludedByPrefix: MutableSet<String> = mutableSetOf()
}

typealias Client = String

class IncomingPermissionsProperties {
    var enabled = false
    var clientIdentityHeaders = listOf("x-service-name")
    var trustedClientIdentityHeader = "x-client-name-trusted"
    var requestIdentificationHeaders = listOf("x-request-id")
    var serviceNameHeader = "x-service-name"
    var sourceIpAuthentication = SourceIpAuthenticationProperties()
    var selectorMatching: MutableMap<Client, SelectorMatching> = mutableMapOf()
    var tlsAuthentication = TlsAuthenticationProperties()
    var clientsAllowedToAllEndpoints = mutableListOf<String>()
    var overlappingPathsFix = false // TODO: to be removed when proved it did not mess up anything
}

class SelectorMatching {
    var header = ""
}

class TlsAuthenticationProperties {
    var wildcardClientIdentifier = "*"
    var servicesAllowedToUseWildcard: MutableSet<String> = mutableSetOf()
    var tlsContextMetadataMatchKey = "acceptMTLS"
    var protocol = TlsProtocolProperties()

    /** if true, a request without a cert will be rejected during handshake and will not reach RBAC filter */
    var requireClientCertificate: Boolean = false
    var validationContextSecretName: String = "validation_context"
    var tlsCertificateSecretName: String = "server_cert"
    var mtlsEnabledTag: String = "mtls:enabled"
    var serviceNameWildcardRegex: String = ".+"
    var sanUriFormat: String = "spiffe://{service-name}"
}

class TlsProtocolProperties {
    var cipherSuites: List<String> = listOf("ECDHE-ECDSA-AES128-GCM-SHA256", "ECDHE-RSA-AES128-GCM-SHA256")
    var minimumVersion = TlsParameters.TlsProtocol.TLSv1_2
    var maximumVersion = TlsParameters.TlsProtocol.TLSv1_2
}

class SourceIpAuthenticationProperties {
    var ipFromServiceDiscovery = IpFromServiceDiscovery()
    var ipFromRange: MutableMap<Client, Set<String>> = mutableMapOf()
}

class IpFromServiceDiscovery {
    var enabledForIncomingServices: List<String> = listOf()
}

class LoadBalancingProperties {
    var canary = CanaryProperties()
    var regularMetadataKey = "lb_regular"
    var localityMetadataKey = "locality"
    var weights = LoadBalancingWeightsProperties()
    var policy = Cluster.LbPolicy.LEAST_REQUEST
    var useKeysSubsetFallbackPolicy = true
}

class CanaryProperties {
    var enabled = false
    var metadataKey = "canary"
    var headerValue = "1"
}

class LoadBalancingWeightsProperties {
    var enabled = false
}

class RoutesProperties {
    var admin = AdminRouteProperties()
    var status = StatusRouteProperties()
    var authorization = AuthorizationProperties()
}

class ClusterOutlierDetectionProperties {
    var enabled = false
    var consecutive5xx = 5
    var interval: Duration = Duration.ofSeconds(10)
    var baseEjectionTime: Duration = Duration.ofSeconds(30)
    var maxEjectionPercent = 10
    var enforcingConsecutive5xx = 100
    var enforcingSuccessRate = 100
    var successRateMinimumHosts = 5
    var successRateRequestVolume = 100
    var successRateStdevFactor = 1900
    var consecutiveGatewayFailure = 5
    var enforcingConsecutiveGatewayFailure = 0
}

class SecuredRoute {
    var pathPrefix = ""
    var method = ""
}

class AdminRouteProperties {
    var publicAccessEnabled = false
    var pathPrefix = "/status/envoy"
    var token = ""
    var securedPaths: MutableList<SecuredRoute> = ArrayList()
    var disable = AdminDisableProperties()
}

class StatusRouteProperties {
    var enabled = false
    var endpoints: MutableList<EndpointMatch> = mutableListOf()
    var createVirtualCluster = false
}

class EndpointMatch {
    var path = "/status/"
    var matchingType: PathMatchingType = PathMatchingType.PATH_PREFIX
}

class AdminDisableProperties {
    var onHeader = ""
    var responseCode = 403
}

class LocalServiceProperties {
    var idleTimeout: Duration = Duration.ofSeconds(60)
    var responseTimeout: Duration = Duration.ofSeconds(15)
    var connectionIdleTimeout: Duration = Duration.ofSeconds(120)
    var retryPolicy: LocalRetryPoliciesProperties = LocalRetryPoliciesProperties()
}

class LocalRetryPoliciesProperties {
    var default: LocalRetryPolicyProperties = LocalRetryPolicyProperties()
    var perHttpMethod: MutableMap<String, LocalRetryPolicyProperties> = mutableMapOf()
}

class LocalRetryPolicyProperties {
    var enabled = false
    var retryOn: MutableSet<String> = mutableSetOf()
    var numRetries: Int = 1
    var perTryTimeout: Duration = Duration.ofMillis(0)
    var hostSelectionRetryMaxAttempts: Long = 1
    var retriableStatusCodes: MutableSet<Int> = mutableSetOf()
}

class AuthorizationProperties {
    var unauthorizedStatusCode = 401
    var unauthorizedResponseMessage = "You have to be authorized"
}

class ServiceTagsProperties {
    var enabled = false
    var metadataKey = "tag"
    var header = "x-service-tag"
    var routingExcludedTags: MutableList<StringMatcher> = mutableListOf()
    var allowedTagsCombinations: MutableList<ServiceTagsCombinationsProperties> = mutableListOf()
}

class StringMatcher {
    var type: StringMatcherType = StringMatcherType.PREFIX
    var value: String = ""
}

enum class StringMatcherType {
    PREFIX, EXACT, REGEX
}

class ServiceTagsCombinationsProperties {
    var serviceName: String = ""
    var tags: MutableList<String> = mutableListOf()
}

class RoutingProperties {
    var serviceTags = ServiceTagsProperties()
}

class EgressProperties {
    var clusterNotFoundStatusCode = 503
    var handleInternalRedirect = false
    var http2 = Http2Properties()
    var commonHttp = CommonHttpProperties()
    var neverRemoveClusters = true
    var hostHeaderRewriting = HostHeaderRewritingProperties()
    var headersToRemove = mutableListOf<String>()
    var domains = mutableListOf<String>()
}

class IngressProperties {
    var headersToRemove = mutableListOf<String>()
    var addServiceNameHeaderToResponse = false
    var addRequestedAuthorityHeaderToResponse = false
    var serviceNameHeader = "x-service-name"
    var requestedAuthorityHeader = "x-requested-authority"
}

class CommonHttpProperties {
    var idleTimeout: Duration = Duration.ofSeconds(120)
    var connectionIdleTimeout: Duration = Duration.ofSeconds(120)
    var requestTimeout: Duration = Duration.ofSeconds(120)
    var circuitBreakers: CircuitBreakers = CircuitBreakers()
}

class CircuitBreakers {
    var highThreshold = Threshold("HIGH")
    var defaultThreshold = Threshold("DEFAULT")
}

class Threshold(var priority: String) {
    var maxConnections = 1024
    var maxPendingRequests = 1024
    var maxRequests = 1024
    var maxRetries = 3
}

class Http2Properties {
    var enabled = true
    var tagName = "envoy"
}

class EnabledCommunicationModes {
    var ads = true
    var xds = true
}

class HostHeaderRewritingProperties {
    var enabled = false
    var customHostHeader = "x-envoy-original-host"
}

class LocalReplyMapperProperties {
    var enabled = false
    var responseFormat = ResponseFormat()
    var matchers = emptyList<MatcherAndMapper>()
}

class MatcherAndMapper {
    var statusCodeMatcher = ""
    var headerMatcher = HeaderMatcher()
    var responseFlagMatcher = emptyList<String>()
    var responseFormat = ResponseFormat()
    var statusCodeToReturn = 0
    var bodyToReturn = ""
}

class HeaderMatcher {
    var name = ""
    var exactMatch: String = ""
    var regexMatch: String = ""
}

class ResponseFormat {
    var textFormat = ""
    var jsonFormat = ""
    var contentType = ""
}

class DynamicForwardProxyProperties {
    var clusterName = "dynamic_forward_proxy_cluster"
    var dnsLookupFamily = Cluster.DnsLookupFamily.V4_ONLY
    var maxCachedHosts = 1024 // default Envoy's value
    var maxHostTtl = Duration.ofSeconds(300) // default Envoy's value
    var connectionTimeout = Duration.ofSeconds(1)
}

data class OAuthProvider(
    var jwksUri: URI = URI.create("http://localhost"),
    var createCluster: Boolean = false,
    var clusterName: String = "",
    var clusterPort: Int = 443,
    var cacheDuration: Duration = Duration.ofSeconds(300),
    var connectionTimeout: Duration = Duration.ofSeconds(1),
    var matchings: Map<Client, TokenField> = emptyMap()
)

class JwtFilterProperties {
    var forwardJwt: Boolean = true
    var forwardPayloadHeader = "x-oauth-token-validated"
    var payloadInMetadata = "jwt"
    var fieldRequiredInToken = "exp"
    var defaultVerificationType = OAuth.Verification.OFFLINE
    var defaultOAuthPolicy = OAuth.Policy.STRICT
    var providers = mapOf<ProviderName, OAuthProvider>()
}

data class RateLimitProperties(
    var domain: String = "rl",
    var serviceName: String = "ratelimit-grpc"
)

data class RetryPolicyProperties(
    var retryOn: List<String> = emptyList(),
    var numberOfRetries: Int = 1,
    var hostSelectionRetryMaxAttempts: Long = 3,
    var retryHostPredicate: List<RetryHostPredicate> =
        listOf(RetryHostPredicate("envoy.retry_host_predicates.previous_hosts")),
    var retryBackOff: RetryBackOffProperties = RetryBackOffProperties(
        baseInterval = Duration.ofMillis(25)
    ),
    var rateLimitedRetryBackOff: RateLimitedRetryBackOff = RateLimitedRetryBackOff(
        listOf(ResetHeader("Retry-After", "SECONDS"))
    )
)

data class RetryBackOffProperties(
    var baseInterval: Duration
)

data class RateLimitedRetryBackOff(
    var resetHeaders: List<ResetHeader>
)

data class ResetHeader(val name: String, val format: String)

typealias ProviderName = String
typealias TokenField = String
