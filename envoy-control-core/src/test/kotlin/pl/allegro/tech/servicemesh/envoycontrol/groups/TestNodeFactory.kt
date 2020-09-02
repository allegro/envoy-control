@file:Suppress("MatchingDeclarationName")
package pl.allegro.tech.servicemesh.envoycontrol.groups

import com.google.protobuf.ListValue
import com.google.protobuf.NullValue
import com.google.protobuf.Struct
import com.google.protobuf.Value
import io.envoyproxy.envoy.api.v2.core.Node

fun node(
    serviceDependencies: Set<String> = emptySet(),
    ads: Boolean? = null,
    serviceName: String? = null,
    incomingSettings: Boolean = false,
    idleTimeout: String? = null,
    responseTimeout: String? = null,
    connectionIdleTimeout: String? = null,
    healthCheckPath: String? = null,
    healthCheckClusterName: String? = null
): Node {
    val meta = Node.newBuilder().metadataBuilder

    serviceName?.let {
        meta.putFields("service_name", string(serviceName))
    }

    ads?.let {
        meta.putFields("ads", Value.newBuilder().setBoolValue(ads).build())
    }

    if (incomingSettings || serviceDependencies.isNotEmpty()) {
        meta.putFields(
            "proxy_settings",
            proxySettingsProto(
                path = "/endpoint",
                serviceDependencies = serviceDependencies,
                incomingSettings = incomingSettings,
                idleTimeout = idleTimeout,
                responseTimeout = responseTimeout,
                connectionIdleTimeout = connectionIdleTimeout,
                healthCheckPath = healthCheckPath,
                healthCheckClusterName = healthCheckClusterName
            )
        )
    }

    return Node.newBuilder()
        .setMetadata(meta)
        .build()
}

val addedProxySettings = ProxySettings(
    Incoming(
        endpoints = listOf(
            IncomingEndpoint(
                path = "/endpoint",
                clients = setOf(ClientWithSelector("client1"))
            )
        ),
        permissionsEnabled = true
    )
)

fun ProxySettings.with(
    serviceDependencies: Set<ServiceDependency> = emptySet(),
    domainDependencies: Set<DomainDependency> = emptySet(),
    allServicesDependencies: Boolean = false,
    defaultServiceSettings: DependencySettings = DependencySettings()
): ProxySettings {
    return copy(
        outgoing = Outgoing(
            serviceDependencies = serviceDependencies.toList(),
            domainDependencies = domainDependencies.toList(),
            allServicesDependencies = allServicesDependencies,
            defaultServiceSettings = defaultServiceSettings
        )
    )
}

fun accessLogFilterProto(statusCodeFilter: String? = null): Value = struct {
    when {
        statusCodeFilter != null -> putFields("status_code_filter", string(statusCodeFilter))
        else -> putFields("status_code_filter", nullValue)
    }
}

fun proxySettingsProto(
    incomingSettings: Boolean,
    path: String? = null,
    serviceDependencies: Set<String> = emptySet(),
    idleTimeout: String? = null,
    responseTimeout: String? = null,
    connectionIdleTimeout: String? = null,
    healthCheckPath: String? = null,
    healthCheckClusterName: String? = null
): Value = struct {
    if (incomingSettings) {
        putFields("incoming", struct {
            putFields("healthCheck", struct {
                healthCheckPath?.let {
                    putFields("path", string(it))
                }
                healthCheckClusterName?.let {
                    putFields("clusterName", string(it))
                }
            })
            putFields("endpoints", list {
                addValues(incomingEndpointProto(path = path))
            })
            putFields("timeoutPolicy", struct {
                idleTimeout?.let {
                    putFields("idleTimeout", string(it))
                }
                responseTimeout?.let {
                    putFields("responseTimeout", string(it))
                }
                connectionIdleTimeout?.let {
                    putFields("connectionIdleTimeout", string(it))
                }
            })
        })
    }
    if (serviceDependencies.isNotEmpty()) {
        putFields("outgoing", outgoingDependenciesProto {
            withServices(serviceDependencies.toList(), idleTimeout, responseTimeout)
        })
    }
}

class OutgoingDependenciesProtoScope {
    class Dependency(val service: String? = null, val domain: String? = null, val idleTimeout: String? = null, val requestTimeout: String? = null, val handleInternalRedirect: Boolean? = null)

    val dependencies = mutableListOf<Dependency>()

    fun withServices(
        serviceDependencies: List<String> = emptyList(),
        idleTimeout: String? = null,
        responseTimeout: String? = null
    ) = serviceDependencies.forEach { withService(it, idleTimeout, responseTimeout) }

    fun withService(
        serviceName: String,
        idleTimeout: String? = null,
        requestTimeout: String? = null,
        handleInternalRedirect: Boolean? = null
    ) = dependencies.add(
        Dependency(
            service = serviceName,
            idleTimeout = idleTimeout,
            requestTimeout = requestTimeout,
            handleInternalRedirect = handleInternalRedirect
        )
    )

    fun withDomain(
        url: String,
        idleTimeout: String? = null,
        requestTimeout: String? = null
    ) = dependencies.add(
        Dependency(
            domain = url,
            idleTimeout = idleTimeout,
            requestTimeout = requestTimeout
        )
    )

    fun withInvalid(service: String? = null, domain: String? = null) = dependencies.add(
        Dependency(
            service = service,
            domain = domain
        )
    )
}

fun outgoingDependenciesProto(
    closure: OutgoingDependenciesProtoScope.() -> Unit
): Value {
    val scope = OutgoingDependenciesProtoScope().apply(closure)
    return struct {
        putFields("dependencies", list {
            scope.dependencies.forEach {
                addValues(
                    outgoingDependencyProto(
                        service = it.service,
                        domain = it.domain,
                        idleTimeout = it.idleTimeout,
                        requestTimeout = it.requestTimeout,
                        handleInternalRedirect = it.handleInternalRedirect
                    )
                )
            }
        })
    }
}

fun outgoingDependencyProto(
    service: String? = null,
    domain: String? = null,
    handleInternalRedirect: Boolean? = null,
    idleTimeout: String? = null,
    requestTimeout: String? = null
) = struct {
    service?.also { putFields("service", string(service)) }
    domain?.also { putFields("domain", string(domain)) }
    handleInternalRedirect?.also { putFields("handleInternalRedirect", boolean(handleInternalRedirect)) }
    if (idleTimeout != null || requestTimeout != null) {
        putFields("timeoutPolicy", outgoingTimeoutPolicy(idleTimeout, requestTimeout))
    }
}

fun outgoingTimeoutPolicy(idleTimeout: String? = null, requestTimeout: String? = null) = struct {
    idleTimeout?.also { putFields("idleTimeout", string(idleTimeout)) }
    requestTimeout?.also { putFields("requestTimeout", string(requestTimeout)) }
}

fun incomingEndpointProto(
    path: String? = null,
    pathPrefix: String? = null,
    includeNullFields: Boolean = false
): Value = struct {
    when {
        path != null -> string(path)
        includeNullFields -> nullValue
        else -> null
    }?.also {
        putFields("path", it)
    }

    when {
        pathPrefix != null -> string(pathPrefix)
        includeNullFields -> nullValue
        else -> null
    }?.also {
        putFields("pathPrefix", it)
    }

    putFields("clients", list { addValues(string("client1")) })
}

fun struct(fields: Struct.Builder.() -> Unit): Value {
    val builder = Struct.newBuilder()
    fields(builder)
    return Value.newBuilder().setStructValue(builder).build()
}

private fun list(elements: ListValue.Builder.() -> Unit): Value {
    val builder = ListValue.newBuilder()
    elements(builder)
    return Value.newBuilder().setListValue(builder).build()
}

private fun string(value: String): Value {
    return Value.newBuilder().setStringValue(value).build()
}

private fun boolean(value: Boolean): Value {
    return Value.newBuilder().setBoolValue(value).build()
}

private val nullValue: Value = Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build()
