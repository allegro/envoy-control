package pl.allegro.tech.servicemesh.envoycontrol.groups

import com.google.protobuf.Duration
import com.google.protobuf.Struct
import com.google.protobuf.Value
import com.google.protobuf.util.Durations
import io.envoyproxy.controlplane.server.exception.RequestException
import io.grpc.Status
import pl.allegro.tech.servicemesh.envoycontrol.groups.ClientWithSelector.Companion.decomposeClient
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.AccessLogFiltersProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.CommonHttpProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.CompressorProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.RetryPolicyProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotProperties
import pl.allegro.tech.servicemesh.envoycontrol.utils.AccessLogFilterParser
import pl.allegro.tech.servicemesh.envoycontrol.utils.ComparisonFilterSettings
import pl.allegro.tech.servicemesh.envoycontrol.utils.HeaderFilterSettings
import java.net.URL
import java.text.ParseException

open class NodeMetadataValidationException(message: String) :
    RequestException(Status.INVALID_ARGUMENT.withDescription(message))

const val BASE_INTERVAL_MULTIPLIER = 10

class NodeMetadata(metadata: Struct, properties: SnapshotProperties) {
    val serviceName: String? = metadata
        .fieldsMap["service_name"]
        ?.stringValue

    val serviceId: Int? = metadata.fieldsMap["service_id"]?.numberValue?.toInt()

    val discoveryServiceName: String? = metadata
        .fieldsMap["discovery_service_name"]
        ?.stringValue

    val communicationMode = getCommunicationMode(metadata.fieldsMap["ads"])

    val pathNormalizationConfig = getPathNormalization(metadata.fieldsMap["path_normalization"], properties)
    val proxySettings: ProxySettings = ProxySettings(metadata.fieldsMap["proxy_settings"], properties)
    val compressionConfig: CompressionConfig = getCompressionSettings(metadata.fieldsMap["compression"], properties)
}

data class AccessLogFilterSettings(val proto: Value?, val properties: AccessLogFiltersProperties) {
    val statusCodeFilterSettings: ComparisonFilterSettings? = proto?.field("status_code_filter")
        .toComparisonFilter(properties.statusCode)
    val durationFilterSettings: ComparisonFilterSettings? = proto?.field("duration_filter")
        .toComparisonFilter(properties.duration)
    val notHealthCheckFilter: Boolean? = proto?.field("not_health_check_filter")?.boolValue ?: properties.notHealthCheck
    val responseFlagFilter: Iterable<String>? = proto?.field("response_flag_filter")
        .toResponseFlagFilter(properties.responseFlag)
    val headerFilter: HeaderFilterSettings? = proto?.field("header_filter")
        .toHeaderFilter(properties.header)
}

data class ProxySettings(
    val incoming: Incoming = Incoming(),
    val outgoing: Outgoing = Outgoing(),
    val customData: Map<String, Any?> = emptyMap()
) {
    constructor(proto: Value?, properties: SnapshotProperties) : this(
        incoming = proto?.field("incoming").toIncoming(properties),
        outgoing = proto?.field("outgoing").toOutgoing(properties),
        customData = proto?.field("customData").toCustomData()
    )

    fun withIncomingPermissionsDisabled(): ProxySettings = copy(
        incoming = incoming.copy(
            permissionsEnabled = false,
            endpoints = emptyList(),
            roles = emptyList()
        )
    )
}

fun Value.toPathNormalization(snapshotProperties: SnapshotProperties): PathNormalizationPolicy {
    return PathNormalizationPolicy(
        normalizationEnabled = this.field("enabled")?.boolValue ?: snapshotProperties.pathNormalization.enabled,
        mergeSlashes = this.field("mergeSlashes")?.boolValue ?: snapshotProperties.pathNormalization.mergeSlashes,
        pathWithEscapedSlashesAction = this.field("escapedSlashesAction")?.stringValue
            ?: snapshotProperties.pathNormalization.pathWithEscapedSlashesAction
    )
}

fun getPathNormalization(proto: Value?, snapshotProperties: SnapshotProperties): PathNormalizationPolicy {
    val defaultNormalizationConfig = PathNormalizationPolicy(
        snapshotProperties.pathNormalization.enabled,
        snapshotProperties.pathNormalization.mergeSlashes,
        snapshotProperties.pathNormalization.pathWithEscapedSlashesAction
    )
    if (proto == null) {
        return defaultNormalizationConfig
    }
    return PathNormalizationPolicy(
        normalizationEnabled = proto.field("enabled")?.boolValue ?: defaultNormalizationConfig.normalizationEnabled,
        mergeSlashes = proto.field("merge_slashes")?.boolValue ?: defaultNormalizationConfig.mergeSlashes,
        pathWithEscapedSlashesAction = proto.field("path_with_escaped_slashes_action")?.stringValue
            ?: defaultNormalizationConfig.pathWithEscapedSlashesAction
    )
}

fun getCompressionSettings(proto: Value?, snapshotProperties: SnapshotProperties): CompressionConfig {
    val defaultCompressionConfig = CompressionConfig(
        Compressor(snapshotProperties.compression.gzip.enabled, snapshotProperties.compression.gzip.quality),
        Compressor(snapshotProperties.compression.brotli.enabled, snapshotProperties.compression.brotli.quality)
    )
    if (proto == null) {
        return defaultCompressionConfig
    }
    return CompressionConfig(
        proto.field("gzip")?.toCompressorProperties(snapshotProperties.compression.gzip)
            ?: defaultCompressionConfig.gzip,
        proto.field("brotli")?.toCompressorProperties(snapshotProperties.compression.brotli)
            ?: defaultCompressionConfig.brotli,
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

fun Value?.toComparisonFilter(default: String? = null): ComparisonFilterSettings? {
    return (this?.stringValue ?: default)?.let {
        AccessLogFilterParser.parseComparisonFilter(it.uppercase())
    }
}

fun Value?.toResponseFlagFilter(default: String? = null): Iterable<String>? {
    return (this?.stringValue ?: default)?.let {
        AccessLogFilterParser.parseResponseFlagFilter(it.uppercase())
    }
}

fun Value?.toHeaderFilter(default: String? = null): HeaderFilterSettings? {
    return (this?.stringValue ?: default)?.let {
        AccessLogFilterParser.parseHeaderFilter(it)
    }
}

private class RawDependency(val service: String?, val domain: String?, val domainPattern: String?, val value: Value)

private fun defaultRetryPolicy(retryPolicy: RetryPolicyProperties) = if (retryPolicy.enabled) {
    RetryPolicy(
        retryOn = retryPolicy.retryOn,
        numberRetries = retryPolicy.numberOfRetries,
        retryHostPredicate = retryPolicy.retryHostPredicate,
        hostSelectionRetryMaxAttempts = retryPolicy.hostSelectionRetryMaxAttempts,
        rateLimitedRetryBackOff = RateLimitedRetryBackOff(
            retryPolicy.rateLimitedRetryBackOff.resetHeaders.map { ResetHeader(it.name, it.format) }
        ),
        retryBackOff = RetryBackOff(
            Durations.fromMillis(retryPolicy.retryBackOff.baseInterval.toMillis())
        ),
    )
} else {
    null
}

private fun defaultTimeoutPolicy(commonHttpProperties: CommonHttpProperties) = Outgoing.TimeoutPolicy(
    idleTimeout = Durations.fromMillis(commonHttpProperties.idleTimeout.toMillis()),
    connectionIdleTimeout = Durations.fromMillis(commonHttpProperties.connectionIdleTimeout.toMillis()),
    requestTimeout = Durations.fromMillis(commonHttpProperties.requestTimeout.toMillis())
)

private fun defaultDependencySettings(properties: SnapshotProperties) = DependencySettings(
    handleInternalRedirect = properties.egress.handleInternalRedirect,
    timeoutPolicy = defaultTimeoutPolicy(properties.egress.commonHttp),
    retryPolicy = defaultRetryPolicy(properties.retryPolicy)
)

fun Value?.toOutgoing(properties: SnapshotProperties): Outgoing {
    val allServiceDependenciesIdentifier = properties.outgoingPermissions.allServicesDependencies.identifier
    val rawDependencies = this?.field("dependencies")?.list().orEmpty().map(::toRawDependency)
    val allServicesDependencies = toAllServiceDependencies(rawDependencies, allServiceDependenciesIdentifier)
    val defaultSettingsFromProperties = defaultDependencySettings(properties)
    val defaultSettings = this.toSettings(defaultSettingsFromProperties)
    val allServicesDefaultSettings = allServicesDependencies?.value.toSettings(defaultSettings)
    val defaultServices = properties.defaultDependencies.services.map {
        ServiceDependency(
            service = it,
            settings = allServicesDefaultSettings
        )
    }
    val defaultDomains = properties.defaultDependencies.domains.map {
        DomainDependency(
            domain = it,
            settings = defaultSettings
        )
    }
    val services = rawDependencies
        .filter { it.service != null && it.service != allServiceDependenciesIdentifier }
        .map {
            ServiceDependency(
                service = it.service.orEmpty(),
                settings = it.value.toSettings(allServicesDefaultSettings)
            )
        }

    val domains = rawDependencies.filter { it.domain != null }
        .onEach { validateDomainFormat(it, allServiceDependenciesIdentifier) }
        .map { DomainDependency(it.domain.orEmpty(), it.value.toSettings(defaultSettings)) }
    val domainPatterns = rawDependencies.filter { it.domainPattern != null }
        .onEach { validateDomainPatternFormat(it) }
        .map { DomainPatternDependency(it.domainPattern.orEmpty(), it.value.toSettings(defaultSettings)) }
    return Outgoing(
        serviceDependencies = services + defaultServices,
        domainDependencies = domains + defaultDomains,
        domainPatternDependencies = domainPatterns,
        defaultServiceSettings = allServicesDefaultSettings,
        allServicesDependencies = allServicesDependencies != null
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
    val retryPolicy = this?.field("retryPolicy")?.toRetryPolicy(defaultSettings.retryPolicy)
    val routingPolicy = this?.field("routingPolicy")?.toRoutingPolicy(defaultSettings.routingPolicy)

    val shouldAllBeDefault = handleInternalRedirect == null &&
        rewriteHostHeader == null &&
        timeoutPolicy == null &&
        retryPolicy == null &&
        routingPolicy == null

    return if (shouldAllBeDefault) {
        defaultSettings
    } else {
        DependencySettings(
            handleInternalRedirect = handleInternalRedirect ?: defaultSettings.handleInternalRedirect,
            timeoutPolicy = timeoutPolicy ?: defaultSettings.timeoutPolicy,
            rewriteHostHeader = rewriteHostHeader ?: defaultSettings.rewriteHostHeader,
            retryPolicy = retryPolicy ?: defaultSettings.retryPolicy,
            routingPolicy = routingPolicy ?: defaultSettings.routingPolicy
        )
    }
}

private fun Value.toRetryPolicy(defaultRetryPolicy: RetryPolicy?): RetryPolicy {
    return RetryPolicy(
        retryOn = this.field("retryOn")?.listValue?.valuesList?.map { it.stringValue } ?: defaultRetryPolicy?.retryOn,
        hostSelectionRetryMaxAttempts = this.field("hostSelectionRetryMaxAttempts")?.numberValue?.toLong()
            ?: defaultRetryPolicy?.hostSelectionRetryMaxAttempts,
        numberRetries = this.field("numberRetries")?.numberValue?.toInt() ?: defaultRetryPolicy?.numberRetries,
        retryHostPredicate = this.field("retryHostPredicate")?.listValue?.valuesList?.mapNotNull {
            RetryHostPredicate.parse(it.field("name")!!.stringValue)
        }?.toList() ?: defaultRetryPolicy?.retryHostPredicate,
        perTryTimeoutMs = this.field("perTryTimeoutMs")?.numberValue?.toLong(),
        retryBackOff = this.field("retryBackOff")?.structValue?.let {
            RetryBackOff(
                baseInterval = it.fieldsMap["baseInterval"]?.toDuration(),
                maxInterval = it.fieldsMap["maxInterval"]?.toDuration()
            )
        } ?: defaultRetryPolicy?.retryBackOff,
        rateLimitedRetryBackOff = this.field("rateLimitedRetryBackOff")?.structValue?.let {
            RateLimitedRetryBackOff(
                it.fieldsMap["resetHeaders"]?.listValue?.valuesList?.mapNotNull(::mapProtoToResetHeader)
            )
        } ?: defaultRetryPolicy?.rateLimitedRetryBackOff,
        retryableStatusCodes = this.field("retryableStatusCodes")?.listValue?.valuesList?.map {
            it.numberValue.toInt()
        },
        retryableHeaders = this.field("retryableHeaders")?.listValue?.valuesList?.map {
            it.stringValue
        },
        methods = mapProtoToMethods(this)
    )
}

private fun Value.toRoutingPolicy(defaultRoutingPolicy: RoutingPolicy): RoutingPolicy {
    return RoutingPolicy(
        autoServiceTag = this.field("autoServiceTag")?.boolValue
            ?: defaultRoutingPolicy.autoServiceTag,
        serviceTagPreference = this.field("serviceTagPreference")?.list()?.map { it.stringValue }
            ?: defaultRoutingPolicy.serviceTagPreference,
        fallbackToAnyInstance = this.field("fallbackToAnyInstance")?.boolValue
            ?: defaultRoutingPolicy.fallbackToAnyInstance
    )
}

private fun mapProtoToResetHeader(resetHeaders: Value): ResetHeader? {
    return resetHeaders.structValue?.let { header ->
        val name = header.fieldsMap["name"]?.stringValue
        val format = header.fieldsMap["format"]?.stringValue
        if (name == null || format == null) {
            null
        } else {
            ResetHeader(name, format)
        }
    }
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
        pathNormalizationPolicy = this?.field("pathNormalizationPolicy")?.toPathNormalization(properties),
        healthCheck = this?.field("healthCheck").toHealthCheck(),
        roles = this?.field("roles")?.list().orEmpty().map { Role(it) },
        timeoutPolicy = this?.field("timeoutPolicy").toIncomingTimeoutPolicy(),
        unlistedEndpointsPolicy = this?.field("unlistedEndpointsPolicy").toUnlistedPolicy()
    )
}

fun Value?.toCompressorProperties(properties: CompressorProperties): Compressor {
    val enabled = this?.field("enabled")?.boolValue
    val quality = this?.field("quality")?.numberValue?.toInt()
    return Compressor(
        enabled ?: properties.enabled,
        quality ?: properties.quality
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
    val paths = this.field("paths")?.list().orEmpty().map { it.stringValue }.toSet()

    if (isMoreThanOnePropertyDefined(paths, path, pathPrefix, pathRegex)) {
        throw NodeMetadataValidationException(
            "Precisely one of 'paths', 'path', 'pathPrefix' or 'pathRegex' field is allowed"
        )
    }

    val methods = this.field("methods")?.list().orEmpty().map { it.stringValue }.toSet()
    val clients = this.field("clients")?.list().orEmpty().map { decomposeClient(it.stringValue) }.toSet()
    val unlistedClientsPolicy = this.field("unlistedClientsPolicy").toUnlistedPolicy()
    val oauth = properties.let { this.field("oauth")?.toOAuth(it) }

    return when {
        paths.isNotEmpty() -> IncomingEndpoint(
            paths, "", PathMatchingType.PATH, methods, clients, unlistedClientsPolicy, oauth
        )

        path != null -> IncomingEndpoint(
            paths, path, PathMatchingType.PATH, methods, clients, unlistedClientsPolicy, oauth
        )

        pathPrefix != null -> IncomingEndpoint(
            paths, pathPrefix, PathMatchingType.PATH_PREFIX, methods, clients, unlistedClientsPolicy, oauth
        )

        pathRegex != null -> IncomingEndpoint(
            paths, pathRegex, PathMatchingType.PATH_REGEX, methods, clients, unlistedClientsPolicy, oauth
        )

        else -> throw NodeMetadataValidationException(
            "One of 'paths', 'path', 'pathPrefix' or 'pathRegex' field is required"
        )
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

fun isMoreThanOnePropertyDefined(vararg properties: Any?): Boolean =
    countNonNullAndNotEmptyProperties(properties.toList()) > 1

private fun countNonNullAndNotEmptyProperties(props: List<Any?>): Int = props.filterNotNull().count {
    if (it is Set<*>) {
        it.isNotEmpty()
    } else {
        true
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

fun Value?.toCustomData(): Map<String, Any?> {
    return when (this?.kindCase) {
        Value.KindCase.STRUCT_VALUE -> this.toMap()
        else -> emptyMap()
    }
}

private fun Value.toMap(): Map<String, Any?> {
    return this.structValue.fieldsMap.map { it.key to it.value.toCustomDataValue() }.toMap()
}

private fun Value?.toCustomDataValue(): Any? {
    return when (this?.kindCase) {
        Value.KindCase.BOOL_VALUE -> this.boolValue
        Value.KindCase.LIST_VALUE -> this.listValue.valuesList.map { it.toCustomDataValue() }
        Value.KindCase.STRING_VALUE -> this.stringValue
        Value.KindCase.NUMBER_VALUE -> this.numberValue
        Value.KindCase.STRUCT_VALUE -> this.toMap()
        else -> null
    }
}

data class Incoming(
    val endpoints: List<IncomingEndpoint> = emptyList(),
    val rateLimitEndpoints: List<IncomingRateLimitEndpoint> = emptyList(),
    val permissionsEnabled: Boolean = false,
    val healthCheck: HealthCheck = HealthCheck(),
    val roles: List<Role> = emptyList(),
    val pathNormalizationPolicy: PathNormalizationPolicy? = null,
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
    val retryPolicy: RetryPolicy? = RetryPolicy(),
    val routingPolicy: RoutingPolicy = RoutingPolicy()
)

data class RetryPolicy(
    val retryOn: List<String>? = emptyList(),
    val hostSelectionRetryMaxAttempts: Long? = null,
    val numberRetries: Int? = null,
    val retryHostPredicate: List<RetryHostPredicate>? = null,
    val perTryTimeoutMs: Long? = null,
    val retryBackOff: RetryBackOff? = null,
    val rateLimitedRetryBackOff: RateLimitedRetryBackOff? = null,
    val retryableStatusCodes: List<Int>? = null,
    val retryableHeaders: List<String>? = null,
    val methods: Set<String>? = null
)

data class RoutingPolicy(
    val autoServiceTag: Boolean = false,
    val serviceTagPreference: List<String> = emptyList(),
    val fallbackToAnyInstance: Boolean = false
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

data class RateLimitedRetryBackOff(
    val resetHeaders: List<ResetHeader>? = null
)

data class ResetHeader(val name: String, val format: String)

enum class RetryHostPredicate(val predicateName: String) {
    OMIT_CANARY_HOST("envoy.retry_host_predicates.omit_canary_hosts"),
    OMIT_HOST_METADATA("envoy.retry_host_predicates.omit_host_metadata"),
    PREVIOUS_HOSTS("envoy.retry_host_predicates.previous_hosts");

    companion object {
        fun parse(value: String): RetryHostPredicate? {
            return values().find { it.predicateName.equals(value, true) || it.name.equals(value, true) }
        }
    }
}

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

data class ClientWithSelector private constructor(
    val name: String,
    val selector: String? = null,
    val negated: Boolean = false
) : Comparable<ClientWithSelector> {

    companion object {
        const val NEGATION_PREFIX = "!"
        fun create(name: String, selector: String? = null): ClientWithSelector {
            return ClientWithSelector(
                name, selector?.removePrefix(NEGATION_PREFIX), selector?.startsWith(
                    NEGATION_PREFIX
                ) ?: false
            )
        }

        fun decomposeClient(client: ClientComposite): ClientWithSelector {
            val parts = client.split(":", ignoreCase = false, limit = 2)
            return if (parts.size == 2) {
                ClientWithSelector.create(parts[0], parts[1])
            } else {
                ClientWithSelector.create(client, null)
            }
        }
    }

    fun compositeName(): ClientComposite {
        return if (selector != null) {
            if (negated) {
                "$name:$NEGATION_PREFIX$selector"
            } else {
                "$name:$selector"
            }
        } else {
            name
        }
    }

    override fun compareTo(other: ClientWithSelector): Int {
        return this.compositeName().compareTo(other.compositeName())
    }
}

data class IncomingEndpoint(
    override val paths: Set<String> = emptySet(),
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
    val paths: Set<String>
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
