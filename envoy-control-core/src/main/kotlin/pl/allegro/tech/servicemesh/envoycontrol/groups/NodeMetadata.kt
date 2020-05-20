package pl.allegro.tech.servicemesh.envoycontrol.groups

import com.google.protobuf.Duration
import com.google.protobuf.Struct
import com.google.protobuf.Value
import com.google.protobuf.util.Durations
import io.envoyproxy.controlplane.server.exception.RequestException
import io.envoyproxy.envoy.config.filter.accesslog.v2.ComparisonFilter
import io.grpc.Status
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.AccessLogFilterProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotProperties
import java.net.URL
import java.text.ParseException

open class NodeMetadataValidationException(message: String) :
    RequestException(Status.INVALID_ARGUMENT.withDescription(message))

class NodeMetadata(metadata: Struct, properties: SnapshotProperties) {
    val serviceName: String? = metadata
        .fieldsMap["service_name"]
        ?.stringValue

    val communicationMode = getCommunicationMode(metadata.fieldsMap["ads"])

    val proxySettings: ProxySettings = ProxySettings(metadata.fieldsMap["proxy_settings"], properties)

    val accessLogFilter: AccessLogFilter = AccessLogFilter(
        metadata.fieldsMap["access_log_filter"],
        properties.accessLogFilterProperties
    )
}

data class AccessLogFilter(
    val statusCodeFilter: StatusCodeFilter?
) {
    constructor(proto: Value?, properties: AccessLogFilterProperties) : this(
        statusCodeFilter = proto?.field("status_code_filter").toStatusCodeFilter(properties)
    )

    data class StatusCodeFilter(
        val comparisonOP: ComparisonFilter.Op,
        val comparisonCode: Int
    )
}

data class ProxySettings(
    val incoming: Incoming = Incoming(),
    val outgoing: Outgoing = Outgoing()
) {
    constructor(proto: Value?, properties: SnapshotProperties) : this(
        incoming = proto?.field("incoming").toIncoming(),
        outgoing = proto?.field("outgoing").toOutgoing(properties)
    )

    fun isEmpty() = this == ProxySettings()

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

fun Value?.toStatusCodeFilter(properties: AccessLogFilterProperties): AccessLogFilter.StatusCodeFilter? {
    val value = this?.stringValue

    if (value != null) {
        var op: ComparisonFilter.Op? = null
        val regex = """((le)|(eq)|(ge)){1}:(\d{3})""".toRegex()

        val matchResult = regex.matchEntire(value.toLowerCase())
        matchResult?.takeIf { !it.groupValues.isEmpty() }?.apply {
            when (matchResult.groupValues.get(properties.operatorIndex)) {
                "le" -> op = ComparisonFilter.Op.LE
                "eq" -> op = ComparisonFilter.Op.EQ
                "ge" -> op = ComparisonFilter.Op.GE
            }
            return AccessLogFilter.StatusCodeFilter(
                comparisonOP = op!!,
                comparisonCode = matchResult.groupValues.get(properties.codeIndex).toInt()
            )
        } ?: run {
            throw NodeMetadataValidationException(
                "Access log filter status code doe not match pattern: ((le)|(eq)|(ge)){1}:(\\d{3})"
            )
        }
    }
    return null
}

private fun Value?.toOutgoing(properties: SnapshotProperties): Outgoing {
    return Outgoing(
        dependencies = this?.field("dependencies")?.list().orEmpty().map { it.toDependency(properties) }
    )
}

fun Value.toDependency(properties: SnapshotProperties = SnapshotProperties()): Dependency {
    val service = this.field("service")?.stringValue
    val domain = this.field("domain")?.stringValue
    val handleInternalRedirect = this.field("handleInternalRedirect")?.boolValue
        ?: properties.egress.handleInternalRedirect
    val timeoutPolicy = this.field("timeoutPolicy")?.toOutgoingTimeoutPolicy(properties)
        ?: Outgoing.TimeoutPolicy(
            idleTimeout = Durations.fromMillis(properties.egress.commonHttp.idleTimeout.toMillis()),
            requestTimeout = Durations.fromMillis(properties.egress.commonHttp.requestTimeout.toMillis())
        )
    val rewriteHostHeader = this.field("rewriteHostHeader")?.boolValue ?: false

    val settings = DependencySettings(handleInternalRedirect, timeoutPolicy, rewriteHostHeader)

    return when {
        service == null && domain == null || service != null && domain != null ->
            throw NodeMetadataValidationException(
                "Define either 'service' or 'domain' as an outgoing dependency"
            )
        service != null -> ServiceDependency(service, settings)
        domain.orEmpty().startsWith("http://") -> DomainDependency(domain.orEmpty(), settings)
        domain.orEmpty().startsWith("https://") -> DomainDependency(domain.orEmpty(), settings)
        else -> throw NodeMetadataValidationException(
            "Unsupported protocol for domain dependency for domain $domain"
        )
    }
}

fun Value?.toIncoming(): Incoming {
    val endpointsField = this?.field("endpoints")?.list()
    return Incoming(
        endpoints = endpointsField.orEmpty().map { it.toIncomingEndpoint() },
        // if there is no endpoint field defined in metadata, we allow for all traffic
        permissionsEnabled = endpointsField != null,
        healthCheck = this?.field("healthCheck").toHealthCheck(),
        roles = this?.field("roles")?.list().orEmpty().map { Role(it) },
        timeoutPolicy = this?.field("timeoutPolicy").toIncomingTimeoutPolicy()
    )
}

fun Value?.toHealthCheck(): HealthCheck {
    val path = this?.field("path")?.stringValue
    val clusterName = this?.field("clusterName")?.stringValue ?: "local_service_health_check"

    return when {
        path != null -> HealthCheck(path = path, clusterName = clusterName)
        else -> HealthCheck()
    }
}

fun Value.toIncomingEndpoint(): IncomingEndpoint {
    val pathPrefix = this.field("pathPrefix")?.stringValue
    val path = this.field("path")?.stringValue

    if (pathPrefix != null && path != null) {
        throw NodeMetadataValidationException("Precisely one of 'path' and 'pathPrefix' field is allowed")
    }

    val methods = this.field("methods")?.list().orEmpty().map { it.stringValue }.toSet()
    val clients = this.field("clients")?.list().orEmpty().map { it.stringValue }.toSet()

    return when {
        path != null -> IncomingEndpoint(path, PathMatchingType.PATH, methods, clients)
        pathPrefix != null -> IncomingEndpoint(pathPrefix, PathMatchingType.PATH_PREFIX, methods, clients)
        else -> throw NodeMetadataValidationException("One of 'path' or 'pathPrefix' field is required")
    }
}

private fun Value?.toIncomingTimeoutPolicy(): Incoming.TimeoutPolicy {
    val idleTimeout: Duration? = this?.field("idleTimeout")?.toDuration()
    val responseTimeout: Duration? = this?.field("responseTimeout")?.toDuration()
    val connectionIdleTimeout: Duration? = this?.field("connectionIdleTimeout")?.toDuration()

    return Incoming.TimeoutPolicy(idleTimeout, responseTimeout, connectionIdleTimeout)
}

private fun Value?.toOutgoingTimeoutPolicy(properties: SnapshotProperties): Outgoing.TimeoutPolicy {
    val idleTimeout: Duration? = this?.field("idleTimeout")?.toDuration()
        ?: Durations.fromMillis(properties.egress.commonHttp.idleTimeout.toMillis())
    val requestTimeout: Duration? = this?.field("requestTimeout")?.toDuration()
        ?: Durations.fromMillis(properties.egress.commonHttp.requestTimeout.toMillis())

    return Outgoing.TimeoutPolicy(idleTimeout, requestTimeout)
}

@Suppress("SwallowedException")
fun Value.toDuration(): Duration? {
    return when (this.kindCase) {
        Value.KindCase.NUMBER_VALUE -> throw NodeMetadataValidationException("Timeout definition has number format" +
            " but should be in string format and ends with 's'")
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
    val permissionsEnabled: Boolean = false,
    val healthCheck: HealthCheck = HealthCheck(),
    val roles: List<Role> = emptyList(),
    val timeoutPolicy: TimeoutPolicy = TimeoutPolicy(
        idleTimeout = null, responseTimeout = null, connectionIdleTimeout = null
    )
) {

    data class TimeoutPolicy(
        val idleTimeout: Duration?,
        val responseTimeout: Duration?,
        val connectionIdleTimeout: Duration?
    )
}

data class Outgoing(
    val dependencies: List<Dependency> = emptyList()
) {
    fun containsDependencyForService(service: String) = serviceDependencies.containsKey(service)

    // not declared in primary constructor to exclude from equals(), copy(), etc.
    private val domainDependencies: Map<String, DomainDependency> = dependencies
        .filterIsInstance<DomainDependency>()
        .map { it.domain to it }
        .toMap()

    private val serviceDependencies: Map<String, ServiceDependency> = dependencies
        .filterIsInstance<ServiceDependency>()
        .map { it.service to it }
        .toMap()

    fun getDomainDependencies(): Collection<DomainDependency> = domainDependencies.values

    fun getServiceDependencies(): Collection<ServiceDependency> = serviceDependencies.values

    data class TimeoutPolicy(
        val idleTimeout: Duration?,
        val requestTimeout: Duration?
    )
}

interface Dependency

data class ServiceDependency(
    val service: String,
    val settings: DependencySettings = DependencySettings()
) : Dependency

data class DomainDependency(
    val domain: String,
    val settings: DependencySettings = DependencySettings()
) : Dependency {
    val uri = URL(domain)

    fun getPort(): Int = uri.port.takeIf { it != -1 } ?: uri.defaultPort

    fun getHost(): String = uri.host

    fun useSsl() = uri.protocol == "https"

    fun getClusterName(): String {
        val clusterName = getHost() + ":" + getPort()
        return clusterName.replace(".", "_").replace(":", "_")
    }

    fun getRouteDomain(): String = if (uri.port != -1) getHost() + ":" + getPort() else getHost()
}

data class DependencySettings(
    val handleInternalRedirect: Boolean = false,
    val timeoutPolicy: Outgoing.TimeoutPolicy? = null,
    val rewriteHostHeader: Boolean = false
)

data class Role(
    val name: String?,
    val clients: Set<String>
) {
    constructor(proto: Value) : this(
        name = proto.field("name")?.stringValue,
        clients = proto.field("clients")?.list().orEmpty().map { it.stringValue }.toSet()
    )
}

data class HealthCheck(
    val path: String = "",
    val clusterName: String = "local_service_health_check"
) {
    fun hasCustomHealthCheck() = !path.isBlank()
}

data class IncomingEndpoint(
    override val path: String = "",
    override val pathMatchingType: PathMatchingType = PathMatchingType.PATH,
    override val methods: Set<String> = emptySet(),
    val clients: Set<String> = emptySet()
) : EndpointBase

enum class PathMatchingType {
    PATH, PATH_PREFIX
}

enum class CommunicationMode {
    ADS, XDS
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
