package pl.allegro.tech.servicemesh.envoycontrol.groups

import com.google.protobuf.Duration
import com.google.protobuf.Struct
import com.google.protobuf.Value
import com.google.protobuf.util.Durations
import io.envoyproxy.controlplane.server.exception.RequestException
import io.grpc.Status
import java.net.URL

open class NodeMetadataValidationException(message: String)
    : RequestException(Status.INVALID_ARGUMENT.withDescription(message))

class NodeMetadata(metadata: Struct) {
    val serviceName: String? = metadata
            .fieldsMap["service_name"]
            ?.stringValue

    val proxySettings: ProxySettings = ProxySettings(metadata.fieldsMap["proxy_settings"])
}

data class ProxySettings(
    val incoming: Incoming = Incoming(),
    val outgoing: Outgoing = Outgoing()
) {
    constructor(proto: Value?) : this(
        incoming = proto?.field("incoming").toIncoming(),
        outgoing = proto?.field("outgoing").toOutgoing()
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

private fun Value?.toOutgoing(): Outgoing {
    return Outgoing(
        dependencies = this?.field("dependencies")?.list().orEmpty().map { it.toDependency() }
    )
}

fun Value.toDependency(): Dependency {
    val service = this.field("service")?.stringValue
    val domain = this.field("domain")?.stringValue

    return when {
        service == null && domain == null || service != null && domain != null ->
            throw NodeMetadataValidationException(
                "Define either 'service' or 'domain' as an outgoing dependency"
            )
        service != null -> ServiceDependency(service)
        domain.orEmpty().startsWith("http://") -> DomainDependency(domain.orEmpty())
        domain.orEmpty().startsWith("https://") -> DomainDependency(domain.orEmpty())
        else -> throw NodeMetadataValidationException(
            "Unsupported protocol for domain dependency for domain $domain"
        )
    }
}

private fun Value?.toIncoming(): Incoming {
    val endpointsField = this?.field("endpoints")?.list()
    return Incoming(
        endpoints = endpointsField.orEmpty().map { it.toIncomingEndpoint() },
        // if there is no endpoint field defined in metadata, we allow for all traffic
        permissionsEnabled = endpointsField != null,
        roles = this?.field("roles")?.list().orEmpty().map { Role(it) },
        timeoutPolicy = this?.field("timeoutPolicy").toTimeoutPolicy()
    )
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

private fun Value?.toTimeoutPolicy(): TimeoutPolicy {
    val idleTimeout: Duration? = this?.field("idleTimeout")?.stringValue
        ?.takeIf { it.isNotBlank() }
        ?.let { Durations.parse(it) }
    val responseTimeout: Duration? = this?.field("responseTimeout")?.stringValue
        ?.takeIf { it.isNotBlank() }
        ?.let { Durations.parse(it) }

    return TimeoutPolicy(idleTimeout, responseTimeout)
}

data class Incoming(
    val endpoints: List<IncomingEndpoint> = emptyList(),
    val permissionsEnabled: Boolean = false,
    val roles: List<Role> = emptyList(),
    val timeoutPolicy: TimeoutPolicy = TimeoutPolicy(idleTimeout = null, responseTimeout = null)
)

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
}

interface Dependency

data class ServiceDependency(val service: String) : Dependency

data class DomainDependency(val domain: String) : Dependency {
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

data class Role(
    val name: String?,
    val clients: Set<String>
) {
    constructor(proto: Value) : this(
        name = proto.field("name")?.stringValue,
        clients = proto.field("clients")?.list().orEmpty().map { it.stringValue }.toSet()
    )
}

data class TimeoutPolicy(
    val idleTimeout: Duration?,
    val responseTimeout: Duration?
)

data class IncomingEndpoint(
    override val path: String = "",
    override val pathMatchingType: PathMatchingType = PathMatchingType.PATH,
    override val methods: Set<String> = emptySet(),
    val clients: Set<String> = emptySet()
) : EndpointBase

enum class PathMatchingType {
    PATH, PATH_PREFIX
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
