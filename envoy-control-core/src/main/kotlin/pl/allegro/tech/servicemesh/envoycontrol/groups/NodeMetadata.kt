package pl.allegro.tech.servicemesh.envoycontrol.groups

import com.google.protobuf.Duration
import com.google.protobuf.Struct
import com.google.protobuf.Value
import com.google.protobuf.util.Durations
import io.envoyproxy.controlplane.server.exception.RequestException
import io.envoyproxy.envoy.config.filter.accesslog.v2.ComparisonFilter
import io.grpc.Status
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters.AccessLogFilterFactory
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
}

data class AccessLogFilterSettings(
    val statusCodeFilterSettings: StatusCodeFilterSettings?
) {
    constructor(proto: Value?, accessLogFilterFactory: AccessLogFilterFactory) : this(
        statusCodeFilterSettings = proto?.field("status_code_filter").toStatusCodeFilter(accessLogFilterFactory)
    )

    data class StatusCodeFilterSettings(
        val comparisonOperator: ComparisonFilter.Op,
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

fun Value?.toStatusCodeFilter(accessLogFilterFactory: AccessLogFilterFactory):
    AccessLogFilterSettings.StatusCodeFilterSettings? {
    return this?.stringValue?.let {
        accessLogFilterFactory.parseStatusCodeFilter(it.toUpperCase())
    }
}

private class RawDependency(val service: String?, val domain: String?, val value: Value)

fun Value?.toOutgoing(properties: SnapshotProperties): Outgoing {
    val allServiceDependenciesIdentifier = properties.outgoingPermissions.allServicesDependencies.identifier
    val rawDependencies = this?.field("dependencies")?.list().orEmpty().map(::toRawDependency)
    val allServicesDependencies = toAllServiceDependencies(rawDependencies, allServiceDependenciesIdentifier)
    val defaultSettingsFromProperties = DependencySettings(
        handleInternalRedirect = properties.egress.handleInternalRedirect,
        timeoutPolicy = Outgoing.TimeoutPolicy(
            idleTimeout = Durations.fromMillis(properties.egress.commonHttp.idleTimeout.toMillis()),
            requestTimeout = Durations.fromMillis(properties.egress.commonHttp.requestTimeout.toMillis())
        )
    )
    val allServicesDefaultSettings = allServicesDependencies?.value.toSettings(defaultSettingsFromProperties)
    val services = rawDependencies.filter { it.service != null && it.service != allServiceDependenciesIdentifier }
        .map { ServiceDependency(it.service.orEmpty(), it.value.toSettings(allServicesDefaultSettings)) }
    val domains = rawDependencies.filter { it.domain != null }
        .onEach { validateDomainFormat(it, allServiceDependenciesIdentifier) }
        .map { DomainDependency(it.domain.orEmpty(), it.value.toSettings(defaultSettingsFromProperties)) }
    return Outgoing(
        serviceDependencies = services,
        domainDependencies = domains,
        defaultServiceSettings = allServicesDefaultSettings,
        allServicesDependencies = allServicesDependencies != null
    )
}

@Suppress("ComplexCondition")
private fun toRawDependency(it: Value): RawDependency {
    val service = it.field("service")?.stringValue
    val domain = it.field("domain")?.stringValue
    if (service == null && domain == null || service != null && domain != null) {
        throw NodeMetadataValidationException(
            "Define either 'service' or 'domain' as an outgoing dependency"
        )
    }
    return RawDependency(
        service = service,
        domain = domain,
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

    return if (handleInternalRedirect == null && rewriteHostHeader == null && timeoutPolicy == null) {
        defaultSettings
    } else DependencySettings(
        handleInternalRedirect ?: defaultSettings.handleInternalRedirect,
        timeoutPolicy ?: defaultSettings.timeoutPolicy,
        rewriteHostHeader ?: defaultSettings.rewriteHostHeader
    )
}

fun Value?.toIncoming(): Incoming {
    val endpointsField = this?.field("endpoints")?.list()
    return Incoming(
        endpoints = endpointsField.orEmpty().map { it.toIncomingEndpoint() },
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

fun Value.toIncomingEndpoint(): IncomingEndpoint {
    val pathPrefix = this.field("pathPrefix")?.stringValue
    val path = this.field("path")?.stringValue

    if (pathPrefix != null && path != null) {
        throw NodeMetadataValidationException("Precisely one of 'path' and 'pathPrefix' field is allowed")
    }

    val methods = this.field("methods")?.list().orEmpty().map { it.stringValue }.toSet()
    val clients = this.field("clients")?.list().orEmpty().map { decomposeClient(it.stringValue) }.toSet()
    val unlistedClientsPolicy = this.field("unlistedClientsPolicy").toUnlistedPolicy()

    return when {
        path != null -> IncomingEndpoint(path, PathMatchingType.PATH, methods, clients, unlistedClientsPolicy)
        pathPrefix != null -> IncomingEndpoint(
            pathPrefix, PathMatchingType.PATH_PREFIX, methods, clients, unlistedClientsPolicy
        )
        else -> throw NodeMetadataValidationException("One of 'path' or 'pathPrefix' field is required")
    }
}

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
    val requestTimeout = this.field("requestTimeout")?.toDuration()
    if (idleTimeout == null && requestTimeout == null) {
        return default
    }
    return Outgoing.TimeoutPolicy(idleTimeout ?: default.idleTimeout, requestTimeout ?: default.requestTimeout)
}

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
    val serviceDependencies: List<ServiceDependency> = emptyList(),
    val domainDependencies: List<DomainDependency> = emptyList(),
    val allServicesDependencies: Boolean = false,
    val defaultServiceSettings: DependencySettings = DependencySettings()
) {
    data class TimeoutPolicy(
        val idleTimeout: Duration = DEFAULT_IDLE_TIMEOUT,
        val requestTimeout: Duration = DEFAULT_REQUEST_TIMEOUT
    ) {
        companion object {
            val DEFAULT_IDLE_TIMEOUT: Duration = Durations.fromSeconds(120)
            val DEFAULT_REQUEST_TIMEOUT: Duration = Durations.fromSeconds(120)
        }
    }
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
    val timeoutPolicy: Outgoing.TimeoutPolicy = Outgoing.TimeoutPolicy(),
    val rewriteHostHeader: Boolean = false
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
    val unlistedClientsPolicy: Incoming.UnlistedPolicy = Incoming.UnlistedPolicy.BLOCKANDLOG
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
