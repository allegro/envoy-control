package pl.allegro.tech.servicemesh.envoycontrol.groups

import com.google.protobuf.Duration
import com.google.protobuf.Struct
import com.google.protobuf.Value
import com.google.protobuf.util.Durations
import io.envoyproxy.controlplane.server.exception.RequestException
import io.grpc.Status
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.CircuitBreakerProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.EgressProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.util.StatusCodeFilterParser
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.util.StatusCodeFilterSettings
import java.net.URL
import java.text.ParseException

open class NodeMetadataValidationException(message: String) :
    RequestException(Status.INVALID_ARGUMENT.withDescription(message))

const val BASE_INTERVAL_MULTIPLIER = 10

class NodeMetadata(metadata: Struct, properties: SnapshotProperties) {
    val serviceName: String? = metadata
        .fieldsMap["service_name"]
        ?.stringValue

    val discoveryServiceName: String? = metadata
        .fieldsMap["discovery_service_name"]
        ?.stringValue

    val communicationMode = getCommunicationMode(metadata.fieldsMap["ads"])

    val proxySettings: ProxySettings = ProxySettings(metadata.fieldsMap["proxy_settings"], properties)
}

data class AccessLogFilterSettings(val proto: Value?) {
    val statusCodeFilterSettings: StatusCodeFilterSettings? = proto?.field("status_code_filter")
        .toStatusCodeFilter()
}

data class ProxySettings(
    val incoming: Incoming = Incoming(),
    val outgoing: Outgoing = Outgoing()
) {
    constructor(proto: Value?, properties: SnapshotProperties) : this(
        incoming = proto?.field("incoming").toIncoming(properties),
        outgoing = proto?.field("outgoing").toOutgoing(properties)
    )

    fun withIncomingPermissionsDisabled(): ProxySettings = copy(
        incoming = incoming.copy(
            permissionsEnabled = false,
            endpoints = emptyList(),
            roles = emptyList()
        )
    )
}

private fun getCommunicationMode(proto: Value?): CommunicationMode {
    val ads = proto
        ?.boolValue
        ?: false

    return when (ads) {
        true -> CommunicationMode.ADS
        else -> CommunicationMode.XDS
    }
}

fun Value?.toStatusCodeFilter(): StatusCodeFilterSettings? {
    return this?.stringValue?.let {
        StatusCodeFilterParser.parseStatusCodeFilter(it.toUpperCase())
    }
}

private class RawDependency(val service: String?, val domain: String?, val domainPattern: String?, val value: Value)

fun Value?.toOutgoing(properties: SnapshotProperties): Outgoing {
    val allServiceDependenciesIdentifier = properties.outgoingPermissions.allServicesDependencies.identifier
    val rawDependencies = this?.field("dependencies")?.list().orEmpty().map(::toRawDependency)
    val allServicesDependencies = toAllServiceDependencies(rawDependencies, allServiceDependenciesIdentifier)
    val defaultSettingsFromProperties = createDefaultOutgoingProperties(properties.egress)
    val allServicesDefaultSettings = allServicesDependencies?.value.toSettings(defaultSettingsFromProperties)
    val services = rawDependencies.filter { it.service != null && it.service != allServiceDependenciesIdentifier }
        .map {
            ServiceDependency(
                service = it.service.orEmpty(),
                settings = it.value.toSettings(allServicesDefaultSettings)
            )
        }
    val domains = rawDependencies.filter { it.domain != null }
        .onEach { validateDomainFormat(it, allServiceDependenciesIdentifier) }
        .map { DomainDependency(it.domain.orEmpty(), it.value.toSettings(defaultSettingsFromProperties)) }
    val domainPatterns = rawDependencies.filter { it.domainPattern != null }
        .onEach { validateDomainPatternFormat(it) }
        .map { DomainPatternDependency(it.domainPattern.orEmpty(), it.value.toSettings(defaultSettingsFromProperties)) }
    return Outgoing(
        serviceDependencies = services,
        domainDependencies = domains,
        domainPatternDependencies = domainPatterns,
        defaultServiceSettings = allServicesDefaultSettings,
        allServicesDependencies = allServicesDependencies != null
    )
}

private fun createDefaultOutgoingProperties(egress: EgressProperties) : DependencySettings {
    return DependencySettings(
        handleInternalRedirect = egress.handleInternalRedirect,
        timeoutPolicy = egress.commonHttp.let {
            Outgoing.TimeoutPolicy(
                idleTimeout = Durations.fromMillis(it.idleTimeout.toMillis()),
                connectionIdleTimeout = Durations.fromMillis(it.connectionIdleTimeout.toMillis()),
                requestTimeout = Durations.fromMillis(it.requestTimeout.toMillis())
            )
        },
        retryPolicy = egress.retryPolicy.let { RetryPolicy(
            numberRetries = it.numberOfRetries,
            retryHostPredicate = it.retryHostPredicate,
            hostSelectionRetryMaxAttempts = it.hostSelectionRetryMaxAttempts,
            retryBackOff = it.retryBackOff
        ) },
        circuitBreakers = egress.commonHttp.circuitBreakers.let { properties ->
            CircuitBreakers(defaultThreshold = properties.defaultThreshold.toCircuitBreaker(),
                highThreshold = properties.highThreshold.toCircuitBreaker())
        }
    )
}

fun CircuitBreakerProperties.toCircuitBreaker(): CircuitBreaker {
    return CircuitBreaker(
        priority = this.priority,
        maxRequests = this.maxRequests,
        maxPendingRequests = this.maxPendingRequests,
        maxConnections = this.maxConnections,
        maxRetries = this.maxRetries,
        maxConnectionPools = this.maxConnectionPools,
        trackRemaining = this.trackRemaining,
        retryBudget = this.retryBudget?.let {
            RetryBudget(
                budgetPercent = it.budgetPercent,
                minRetryConcurrency = it.minRetryConcurrency
            )
        }
    )
}

@Suppress("ComplexCondition")
private fun toRawDependency(it: Value): RawDependency {
    val service = it.field("service")?.stringValue
    val domain = it.field("domain")?.stringValue
    val domainPattern = it.field("domainPattern")?.stringValue
    var count = 0
    if (!service.isNullOrBlank()) {
        count += 1
    }
    if (!domain.isNullOrBlank()) {
        count += 1
    }
    if (!domainPattern.isNullOrBlank()) {
        count += 1
    }
    if (count != 1) {
        throw NodeMetadataValidationException(
            "Define one of: 'service', 'domain' or 'domainPattern' as an outgoing dependency"
        )
    }
    return RawDependency(
        service = service,
        domain = domain,
        domainPattern = domainPattern,
        value = it
    )
}

private fun validateDomainFormat(
    it: RawDependency,
    allServiceDependenciesIdentifier: String
) {
    val domain = it.domain.orEmpty()
    if (domain == allServiceDependenciesIdentifier) {
        throw NodeMetadataValidationException(
            "Unsupported 'all serviceDependencies identifier' for domain dependency: $domain"
        )
    }
    if (!domain.startsWith("http://") && !domain.startsWith("https://")) {
        throw NodeMetadataValidationException(
            "Unsupported protocol for domain dependency for domain $domain"
        )
    }
}

private fun validateDomainPatternFormat(
    it: RawDependency
) {
    val domainPattern = it.domainPattern.orEmpty()
    if (domainPattern.startsWith("http://") || domainPattern.startsWith("https://")) {
        throw NodeMetadataValidationException(
            "Unsupported format for domainPattern: domainPattern cannot contain a schema like http:// or https://"
        )
    }
}

private fun toAllServiceDependencies(
    rawDependencies: List<RawDependency>,
    allServiceDependenciesIdentifier: String
): RawDependency? {
    val allServicesDependencies = rawDependencies.filter { it.service == allServiceDependenciesIdentifier }.toList()
    if (allServicesDependencies.size > 1) {
        throw NodeMetadataValidationException(
            "Define at most one 'all serviceDependencies identifier' as an service dependency"
        )
    }
    return allServicesDependencies.firstOrNull()
}

private fun Value?.toSettings(defaultSettings: DependencySettings): DependencySettings {
    val handleInternalRedirect = this?.field("handleInternalRedirect")?.boolValue
    val timeoutPolicy = this?.field("timeoutPolicy")?.toOutgoingTimeoutPolicy(defaultSettings.timeoutPolicy)
    val rewriteHostHeader = this?.field("rewriteHostHeader")?.boolValue
    val retryPolicy = this?.field("retryPolicy")?.let { retryPolicy ->
        mapProtoToRetryPolicy(
            retryPolicy,
            defaultSettings.retryPolicy
        )
    }
    val circuitBreakers = this?.field("circuitBreakers")?.toCircuitBreakers(defaultSettings.circuitBreakers)

    val shouldAllBeDefault = handleInternalRedirect == null &&
        rewriteHostHeader == null &&
        timeoutPolicy == null &&
        retryPolicy == null &&
        circuitBreakers == null

    return if (shouldAllBeDefault) {
        defaultSettings
    } else {
        DependencySettings(
            handleInternalRedirect = handleInternalRedirect ?: defaultSettings.handleInternalRedirect,
            timeoutPolicy = timeoutPolicy ?: defaultSettings.timeoutPolicy,
            rewriteHostHeader = rewriteHostHeader ?: defaultSettings.rewriteHostHeader,
            retryPolicy = retryPolicy ?: defaultSettings.retryPolicy,
            circuitBreakers = circuitBreakers ?: defaultSettings.circuitBreakers
        )
    }
}

private fun Value?.toCircuitBreakers(defaultCircuitBreakers: CircuitBreakers): CircuitBreakers {
    return CircuitBreakers(
        defaultThreshold = this?.field("defaultThreshold")?.toCircuitBreaker(defaultCircuitBreakers.defaultThreshold)
            ?: defaultCircuitBreakers.defaultThreshold,
        highThreshold = this?.field("highThreshold")?.toCircuitBreaker(defaultCircuitBreakers.highThreshold)
            ?: defaultCircuitBreakers.highThreshold
    )
}

private fun Value?.toCircuitBreaker(defaultCircuitBreaker: CircuitBreaker?): CircuitBreaker {
    return CircuitBreaker(priority = this?.field("priority")?.stringValue?.let { RoutingPriority.fromString(it) }
        ?: defaultCircuitBreaker?.priority,
        maxRequests = this?.field("maxRequests")?.numberValue?.toInt() ?: defaultCircuitBreaker?.maxRequests,
        maxPendingRequests = this?.field("maxPendingRequests")?.numberValue?.toInt()
            ?: defaultCircuitBreaker?.maxPendingRequests,
        maxConnections = this?.field("maxConnections")?.numberValue?.toInt() ?: defaultCircuitBreaker?.maxConnections,
        maxRetries = this?.field("maxRetries")?.numberValue?.toInt() ?: defaultCircuitBreaker?.maxRetries,
        maxConnectionPools = this?.field("maxConnectionPools")?.numberValue?.toInt()
            ?: defaultCircuitBreaker?.maxConnectionPools,
        trackRemaining = this?.field("trackRemaining")?.boolValue ?: defaultCircuitBreaker?.trackRemaining,
        retryBudget = this?.field("retryBudget")?.toRetryBudget(defaultCircuitBreaker?.retryBudget)
            ?: defaultCircuitBreaker?.retryBudget
    )
}
private fun Value?.toRetryBudget(defaultRetryBudget: RetryBudget?): RetryBudget {
    return RetryBudget(
        budgetPercent = this?.field("budgetPercent")?.numberValue ?: defaultRetryBudget?.budgetPercent,
        minRetryConcurrency = this?.field("minRetryConcurrency")?.numberValue?.toInt()
            ?: defaultRetryBudget?.minRetryConcurrency
    )
}

private fun mapProtoToRetryPolicy(value: Value, defaultRetryPolicy: RetryPolicy): RetryPolicy {
    return RetryPolicy(
        retryOn = value.field("retryOn")?.listValue?.valuesList?.map { it.stringValue },
        hostSelectionRetryMaxAttempts = value.field("hostSelectionRetryMaxAttempts")?.numberValue?.toLong()
            ?: defaultRetryPolicy.hostSelectionRetryMaxAttempts,
        numberRetries = value.field("numberRetries")?.numberValue?.toInt() ?: defaultRetryPolicy.numberRetries,
        retryHostPredicate = value.field("retryHostPredicate")?.listValue?.valuesList?.map {
            RetryHostPredicate(it.field("name")!!.stringValue)
        }?.toList() ?: defaultRetryPolicy.retryHostPredicate,
        perTryTimeoutMs = value.field("perTryTimeoutMs")?.numberValue?.toLong(),
        retryBackOff = value.field("retryBackOff")?.structValue?.let {
            RetryBackOff(
                baseInterval = it.fieldsMap["baseInterval"]?.toDuration(),
                maxInterval = it.fieldsMap["maxInterval"]?.toDuration()
            )
        } ?: defaultRetryPolicy.retryBackOff,
        retryableStatusCodes = value.field("retryableStatusCodes")?.listValue?.valuesList?.map {
            it.numberValue.toInt()
        },
        retryableHeaders = value.field("retryableHeaders")?.listValue?.valuesList?.map {
            it.stringValue
        },
        methods = mapProtoToMethods(value)
    )
}

private fun mapProtoToMethods(methods: Value) =
    methods.field("methods")?.list()?.map { singleMethodAsField ->
        singleMethodAsField.stringValue
    }?.toSet()

fun Value?.toIncoming(properties: SnapshotProperties): Incoming {
    val endpointsField = this?.field("endpoints")?.list()
    val rateLimitEndpointsField = this?.field("rateLimitEndpoints")?.list()
    return Incoming(
        endpoints = endpointsField.orEmpty().map { it.toIncomingEndpoint(properties) },
        rateLimitEndpoints = rateLimitEndpointsField.orEmpty().map(Value::toIncomingRateLimitEndpoint),
        // if there is no endpoint field defined in metadata, we allow for all traffic
        permissionsEnabled = endpointsField != null,
        healthCheck = this?.field("healthCheck").toHealthCheck(),
        roles = this?.field("roles")?.list().orEmpty().map { Role(it) },
        timeoutPolicy = this?.field("timeoutPolicy").toIncomingTimeoutPolicy(),
        unlistedEndpointsPolicy = this?.field("unlistedEndpointsPolicy").toUnlistedPolicy()
    )
}

fun Value?.toUnlistedPolicy() = this?.stringValue
    ?.takeIf { it.isNotEmpty() }
    ?.let {
        when (it) {
            "log" -> Incoming.UnlistedPolicy.LOG
            "blockAndLog" -> Incoming.UnlistedPolicy.BLOCKANDLOG
            else -> throw NodeMetadataValidationException("Invalid UnlistedPolicy value: $it")
        }
    }
    ?: Incoming.UnlistedPolicy.BLOCKANDLOG

fun Value?.toHealthCheck(): HealthCheck {
    val path = this?.field("path")?.stringValue
    val clusterName = this?.field("clusterName")?.stringValue ?: "local_service_health_check"

    return when {
        path != null -> HealthCheck(path = path, clusterName = clusterName)
        else -> HealthCheck()
    }
}

fun Value.toIncomingEndpoint(properties: SnapshotProperties): IncomingEndpoint {
    val pathPrefix = this.field("pathPrefix")?.stringValue
    val path = this.field("path")?.stringValue
    val pathRegex = this.field("pathRegex")?.stringValue

    if (isMoreThanOnePropertyDefined(path, pathPrefix, pathRegex)) {
        throw NodeMetadataValidationException("Precisely one of 'path', 'pathPrefix' or 'pathRegex' field is allowed")
    }

    val methods = this.field("methods")?.list().orEmpty().map { it.stringValue }.toSet()
    val clients = this.field("clients")?.list().orEmpty().map { decomposeClient(it.stringValue) }.toSet()
    val unlistedClientsPolicy = this.field("unlistedClientsPolicy").toUnlistedPolicy()
    val oauth = properties.let { this.field("oauth")?.toOAuth(it) }

    return when {
        path != null -> IncomingEndpoint(path, PathMatchingType.PATH, methods, clients, unlistedClientsPolicy, oauth)
        pathPrefix != null -> IncomingEndpoint(
            pathPrefix, PathMatchingType.PATH_PREFIX, methods, clients, unlistedClientsPolicy, oauth
        )
        pathRegex != null -> IncomingEndpoint(
            pathRegex, PathMatchingType.PATH_REGEX, methods, clients, unlistedClientsPolicy, oauth
        )
        else -> throw NodeMetadataValidationException("One of 'path', 'pathPrefix' or 'pathRegex' field is required")
    }
}

fun Value.toIncomingRateLimitEndpoint(): IncomingRateLimitEndpoint {
    val pathPrefix = this.field("pathPrefix")?.stringValue
    val path = this.field("path")?.stringValue
    val pathRegex = this.field("pathRegex")?.stringValue

    if (isMoreThanOnePropertyDefined(path, pathPrefix, pathRegex)) {
        throw NodeMetadataValidationException("Precisely one of 'path', 'pathPrefix' or 'pathRegex' field is allowed")
    }

    val methods = this.field("methods")?.list().orEmpty().map { it.stringValue }.toSet()
    val clients = this.field("clients")?.list().orEmpty().map { decomposeClient(it.stringValue) }.toSet()
    val rateLimit = this.field("rateLimit").toRateLimit()

    return when {
        path != null -> IncomingRateLimitEndpoint(path, PathMatchingType.PATH, methods, clients, rateLimit)
        pathPrefix != null -> IncomingRateLimitEndpoint(
            pathPrefix, PathMatchingType.PATH_PREFIX, methods, clients, rateLimit
        )
        pathRegex != null -> IncomingRateLimitEndpoint(
            pathRegex, PathMatchingType.PATH_REGEX, methods, clients, rateLimit
        )
        else -> throw NodeMetadataValidationException("One of 'path', 'pathPrefix' or 'pathRegex' field is required")
    }
}

fun isMoreThanOnePropertyDefined(vararg properties: String?): Boolean = properties.filterNotNull().count() > 1

private fun decomposeClient(client: ClientComposite): ClientWithSelector {
    val parts = client.split(":", ignoreCase = false, limit = 2)
    return if (parts.size == 2) {
        ClientWithSelector(parts[0], parts[1])
    } else {
        ClientWithSelector(client, null)
    }
}

private fun Value?.toIncomingTimeoutPolicy(): Incoming.TimeoutPolicy {
    val idleTimeout: Duration? = this?.field("idleTimeout")?.toDuration()
    val responseTimeout: Duration? = this?.field("responseTimeout")?.toDuration()
    val connectionIdleTimeout: Duration? = this?.field("connectionIdleTimeout")?.toDuration()

    return Incoming.TimeoutPolicy(idleTimeout, responseTimeout, connectionIdleTimeout)
}

private fun Value.toOutgoingTimeoutPolicy(default: Outgoing.TimeoutPolicy): Outgoing.TimeoutPolicy {
    val idleTimeout = this.field("idleTimeout")?.toDuration()
    val connectionIdleTimeout = this.field("connectionIdleTimeout")?.toDuration()
    val requestTimeout = this.field("requestTimeout")?.toDuration()
    if (idleTimeout == null && requestTimeout == null && connectionIdleTimeout == null) {
        return default
    }
    return Outgoing.TimeoutPolicy(
        idleTimeout ?: default.idleTimeout,
        connectionIdleTimeout ?: default.connectionIdleTimeout,
        requestTimeout ?: default.requestTimeout
    )
}

private fun Value?.toRateLimit() =
    this?.stringValue ?: throw NodeMetadataValidationException("rateLimit value cannot be null")

private fun Value.toOAuth(properties: SnapshotProperties): OAuth {
    val provider = this.field("provider").toOauthProvider(properties)
    val policy = this.field("policy").toOAuthPolicy(properties.jwt.defaultOAuthPolicy)
    val verification = this.field("verification").toOAuthVerification(properties.jwt.defaultVerificationType)

    return OAuth(provider, verification, policy)
}

fun Value?.toOauthProvider(properties: SnapshotProperties) = this?.stringValue
    ?.takeIf { it.isNotEmpty() }
    ?.let {
        if (properties.jwt.providers.keys.contains(it)) {
            it
        } else {
            throw NodeMetadataValidationException("Invalid OAuth provider value: $it")
        }
    }
    ?: throw NodeMetadataValidationException("OAuth provider value cannot be null")

fun Value?.toOAuthVerification(defaultVerification: OAuth.Verification) = this?.stringValue
    ?.takeIf { it.isNotEmpty() }
    ?.let {
        when (it) {
            "offline" -> OAuth.Verification.OFFLINE
            else -> throw NodeMetadataValidationException("Invalid OAuth verification value: $it")
        }
    }
    ?: defaultVerification

fun Value?.toOAuthPolicy(defaultPolicy: OAuth.Policy) = this?.stringValue
    ?.takeIf { it.isNotEmpty() }
    ?.let {
        when (it) {
            "allowMissing" -> OAuth.Policy.ALLOW_MISSING
            "allowMissingOrFailed" -> OAuth.Policy.ALLOW_MISSING_OR_FAILED
            "strict" -> OAuth.Policy.STRICT
            else -> throw NodeMetadataValidationException("Invalid OAuth policy value: $it")
        }
    }
    ?: defaultPolicy

@Suppress("SwallowedException")
fun Value.toDuration(): Duration? {
    return when (this.kindCase) {
        Value.KindCase.NUMBER_VALUE -> throw NodeMetadataValidationException(
            "Timeout definition has number format" +
                " but should be in string format and ends with 's'"
        )
        Value.KindCase.STRING_VALUE -> {
            try {
                this.stringValue?.takeIf { it.isNotBlank() }?.let { Durations.parse(it) }
            } catch (ex: ParseException) {
                throw NodeMetadataValidationException("Timeout definition has incorrect format: ${ex.message}")
            }
        }
        else -> null
    }
}

data class Incoming(
    val endpoints: List<IncomingEndpoint> = emptyList(),
    val rateLimitEndpoints: List<IncomingRateLimitEndpoint> = emptyList(),
    val permissionsEnabled: Boolean = false,
    val healthCheck: HealthCheck = HealthCheck(),
    val roles: List<Role> = emptyList(),
    val timeoutPolicy: TimeoutPolicy = TimeoutPolicy(
        idleTimeout = null, responseTimeout = null, connectionIdleTimeout = null
    ),
    val unlistedEndpointsPolicy: UnlistedPolicy = UnlistedPolicy.BLOCKANDLOG
) {

    data class TimeoutPolicy(
        val idleTimeout: Duration?,
        val responseTimeout: Duration?,
        val connectionIdleTimeout: Duration?
    )

    enum class UnlistedPolicy {
        LOG, BLOCKANDLOG
    }
}

data class Outgoing(
    private val serviceDependencies: List<ServiceDependency> = emptyList(),
    private val domainDependencies: List<DomainDependency> = emptyList(),
    private val domainPatternDependencies: List<DomainPatternDependency> = emptyList(),
    val allServicesDependencies: Boolean = false,
    val defaultServiceSettings: DependencySettings = DependencySettings()
) {

    // not declared in primary constructor to exclude from equals(), copy(), etc.
    private val deduplicatedDomainDependencies: List<DomainDependency> = domainDependencies
        .map { it.domain to it }
        .toMap().values.toList()

    private val deduplicatedServiceDependencies: List<ServiceDependency> = serviceDependencies
        .map { it.service to it }
        .toMap().values.toList()

    private val deduplicatedDomainPatternDependencies: List<DomainPatternDependency> = domainPatternDependencies
        .map { it.domainPattern to it }
        .toMap().values.toList()

    fun getDomainDependencies(): List<DomainDependency> = deduplicatedDomainDependencies
    fun getServiceDependencies(): List<ServiceDependency> = deduplicatedServiceDependencies
    fun getDomainPatternDependencies(): List<DomainPatternDependency> = deduplicatedDomainPatternDependencies

    data class TimeoutPolicy(
        val idleTimeout: Duration? = null,
        val connectionIdleTimeout: Duration? = null,
        val requestTimeout: Duration? = null
    )
}

// TODO: Make it default method, currently some problems with kotlin version, might upgrade in next PR
interface Dependency {
    fun getPort(): Int
    fun useSsl(): Boolean
}

data class ServiceDependency(
    val service: String,
    val settings: DependencySettings = DependencySettings()
) : Dependency {
    companion object {
        private const val DEFAULT_HTTP_PORT = 80
        private const val DEFAULT_HTTPS_POLICY = false
    }

    override fun getPort() = DEFAULT_HTTP_PORT

    override fun useSsl() = DEFAULT_HTTPS_POLICY
}

data class DomainDependency(
    val domain: String,
    val settings: DependencySettings = DependencySettings()
) : Dependency {
    val uri = URL(domain)

    override fun getPort(): Int = uri.port.takeIf { it != -1 } ?: uri.defaultPort

    override fun useSsl() = uri.protocol == "https"

    fun getHost(): String = uri.host

    fun getClusterName(): String {
        val clusterName = getHost() + ":" + getPort()
        return clusterName.replace(".", "_").replace(":", "_")
    }

    fun getRouteDomain(): String = if (uri.port != -1) getHost() + ":" + getPort() else getHost()
}

data class DomainPatternDependency(
    val domainPattern: String,
    val settings: DependencySettings = DependencySettings()
) : Dependency {
    companion object {
        private const val DEFAULT_HTTP_PORT = 80
        private const val DEFAULT_HTTPS_POLICY = false
    }

    override fun getPort() = DEFAULT_HTTP_PORT

    override fun useSsl() = DEFAULT_HTTPS_POLICY
}

data class DependencySettings(
    val handleInternalRedirect: Boolean = false,
    val timeoutPolicy: Outgoing.TimeoutPolicy = Outgoing.TimeoutPolicy(),
    val rewriteHostHeader: Boolean = false,
    val retryPolicy: RetryPolicy = RetryPolicy(),
    val circuitBreakers: CircuitBreakers = CircuitBreakers()
)

data class CircuitBreakers(
    val defaultThreshold: CircuitBreaker? = null,
    val highThreshold: CircuitBreaker? = null
)

data class CircuitBreaker(
    val priority: RoutingPriority? = null,
    val maxRequests: Int? = null,
    val maxPendingRequests: Int? = null,
    val maxConnections: Int? = null,
    val maxRetries: Int? = null,
    val maxConnectionPools: Int? = null,
    val trackRemaining: Boolean? = null,
    val retryBudget: RetryBudget? = null
)

data class RetryBudget(val budgetPercent: Double? = null, val minRetryConcurrency: Int? = null)

enum class RoutingPriority {
    DEFAULT, HIGH, UNRECOGNIZED;

    companion object {
        fun fromString(value: String): RoutingPriority {
            return when (value.toUpperCase()) {
                "DEFAULT" -> DEFAULT
                "HIGH" -> HIGH
                else -> UNRECOGNIZED
            }
        }
    }
}

data class RetryPolicy(
    val retryOn: List<String>? = null,
    val hostSelectionRetryMaxAttempts: Long? = null,
    val numberRetries: Int? = null,
    val retryHostPredicate: List<RetryHostPredicate>? = null,
    val perTryTimeoutMs: Long? = null,
    val retryBackOff: RetryBackOff? = null,
    val retryableStatusCodes: List<Int>? = null,
    val retryableHeaders: List<String>? = null,
    val methods: Set<String>? = null
)

data class RetryBackOff(
    val baseInterval: Duration? = null,
    val maxInterval: Duration? = null
) {
    constructor(baseInterval: Duration) : this(
        baseInterval = baseInterval,
        maxInterval = Durations.fromMillis(Durations.toMillis(baseInterval).times(BASE_INTERVAL_MULTIPLIER))
    )
}

data class RetryHostPredicate(
    val name: String
)

data class Role(
    val name: String?,
    val clients: Set<ClientWithSelector>
) {
    constructor(proto: Value) : this(
        name = proto.field("name")?.stringValue,
        clients = proto.field("clients")?.list().orEmpty().map { decomposeClient(it.stringValue) }.toSet()
    )
}

data class HealthCheck(
    val path: String = "",
    val clusterName: String = "local_service_health_check"
) {
    fun hasCustomHealthCheck() = !path.isBlank()
}

typealias ClientComposite = String

data class ClientWithSelector(
    val name: String,
    val selector: String? = null
) : Comparable<ClientWithSelector> {
    fun compositeName(): ClientComposite {
        return if (selector != null) {
            "$name:$selector"
        } else {
            name
        }
    }

    override fun compareTo(other: ClientWithSelector): Int {
        return this.compositeName().compareTo(other.compositeName())
    }
}

data class IncomingEndpoint(
    override val path: String = "",
    override val pathMatchingType: PathMatchingType = PathMatchingType.PATH,
    override val methods: Set<String> = emptySet(),
    val clients: Set<ClientWithSelector> = emptySet(),
    val unlistedClientsPolicy: Incoming.UnlistedPolicy = Incoming.UnlistedPolicy.BLOCKANDLOG,
    val oauth: OAuth? = null
) : EndpointBase

data class IncomingRateLimitEndpoint(
    val path: String,
    val pathMatchingType: PathMatchingType = PathMatchingType.PATH,
    val methods: Set<String> = emptySet(),
    val clients: Set<ClientWithSelector> = emptySet(),
    val rateLimit: String = ""
)

enum class PathMatchingType {
    PATH, PATH_PREFIX, PATH_REGEX
}

enum class CommunicationMode {
    ADS, XDS
}

data class OAuth(
    val provider: String = "",
    val verification: Verification = Verification.OFFLINE,
    val policy: Policy = Policy.ALLOW_MISSING
) {

    enum class Verification {
        OFFLINE, ONLINE
    }

    enum class Policy {
        STRICT, ALLOW_MISSING, ALLOW_MISSING_OR_FAILED
    }
}

interface EndpointBase {
    val path: String
    val pathMatchingType: PathMatchingType
    val methods: Set<String>
}

// We don't distinguish between absence of the field and the field with explicit null value.
// So we map both cases to the same output - null
private fun Value.field(key: String): Value? = this.structValue?.fieldsMap?.get(key)
    ?.takeIf { it.kindCase != Value.KindCase.NULL_VALUE }

private fun Value.list(): List<Value>? = this.listValue?.valuesList

fun List<IncomingRateLimitEndpoint>.containsGlobalRateLimits(): Boolean = isNotEmpty()
