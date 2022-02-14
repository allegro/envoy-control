@file:Suppress("MatchingDeclarationName")

package pl.allegro.tech.servicemesh.envoycontrol.groups

import com.google.protobuf.ListValue
import com.google.protobuf.NullValue
import com.google.protobuf.Struct
import com.google.protobuf.Value
import com.google.protobuf.util.Durations
import io.envoyproxy.envoy.config.core.v3.Node as NodeV3

fun nodeV3(
    serviceDependencies: Set<String> = emptySet(),
    ads: Boolean? = null,
    serviceName: String? = null,
    discoveryServiceName: String? = null,
    incomingSettings: Boolean = false,
    clients: List<String> = listOf("client1"),
    idleTimeout: String? = null,
    responseTimeout: String? = null,
    connectionIdleTimeout: String? = null,
    healthCheckPath: String? = null,
    healthCheckClusterName: String? = null,
    rateLimit: String? = null
): NodeV3 {
    val meta = NodeV3.newBuilder().metadataBuilder

    serviceName?.let {
        meta.putFields("service_name", string(serviceName))
    }

    discoveryServiceName?.let {
        meta.putFields("discovery_service_name", string(discoveryServiceName))
    }

    ads?.let {
        meta.putFields("ads", Value.newBuilder().setBoolValue(ads).build())
    }

    if (incomingSettings || serviceDependencies.isNotEmpty()) {
        meta.putFields(
            "proxy_settings",
            proxySettingsProto(
                path = "/endpoint",
                clients = clients,
                serviceDependencies = serviceDependencies,
                incomingSettings = incomingSettings,
                idleTimeout = idleTimeout,
                responseTimeout = responseTimeout,
                connectionIdleTimeout = connectionIdleTimeout,
                healthCheckPath = healthCheckPath,
                healthCheckClusterName = healthCheckClusterName,
                rateLimit = rateLimit
            )
        )
    }

    return NodeV3.newBuilder()
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
    defaultServiceSettings: DependencySettings = DependencySettings(
        timeoutPolicy = Outgoing.TimeoutPolicy(
            Durations.fromSeconds(120),
            Durations.fromSeconds(120),
            Durations.fromSeconds(120)
        )
    ),
    rateLimitEndpoints: List<IncomingRateLimitEndpoint> = emptyList()
): ProxySettings {
    return copy(
        incoming = incoming.copy(
            rateLimitEndpoints = rateLimitEndpoints
        ),
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
    healthCheckClusterName: String? = null,
    clients: List<String> = listOf("client1"),
    rateLimit: String? = null
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
                addValues(incomingEndpointProto(path = path, clients = clients))
            })
            if (rateLimit != null) {
                putFields("rateLimitEndpoints", list {
                    addValues(incomingRateLimitEndpointProto(path = path, rateLimit = rateLimit))
                })
            }
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

data class RetryPolicyInput(
    val retryOn: String? = null,
    val hostSelectionRetryMaxAttempts: Int? = null,
    val numberRetries: Int? = null,
    val retryHostPredicate: List<RetryHostPredicateInput>? = null,
    val perTryTimeoutMs: Int? = null,
    val retryBackOff: RetryBackOffInput? = null,
    val retryableStatusCodes: List<Int>? = null,
    val retryableHeaders: List<String>? = null
)

data class RetryBackOffInput(
    val baseInterval: String? = null,
    val maxInterval: String? = null
)

data class RetryHostPredicateInput(
    val name: String?
)

class OutgoingDependenciesProtoScope {
    class Dependency(
        val service: String? = null,
        val domain: String? = null,
        val domainPattern: String? = null,
        val idleTimeout: String? = null,
        val connectionIdleTimeout: String? = null,
        val requestTimeout: String? = null,
        val handleInternalRedirect: Boolean? = null,
        val retryPolicy: RetryPolicyInput? = null,
        val methods: Set<String>? = null
    )

    val dependencies = mutableListOf<Dependency>()

    fun withServices(
        serviceDependencies: List<String> = emptyList(),
        idleTimeout: String? = null,
        responseTimeout: String? = null
    ) = serviceDependencies.forEach { withService(it, idleTimeout, responseTimeout) }

    fun withService(
        serviceName: String,
        idleTimeout: String? = null,
        connectionIdleTimeout: String? = null,
        requestTimeout: String? = null,
        handleInternalRedirect: Boolean? = null,
        retryPolicy: RetryPolicyInput? = null,
        methods: Set<String>? = null
    ) = dependencies.add(
        Dependency(
            service = serviceName,
            idleTimeout = idleTimeout,
            connectionIdleTimeout = connectionIdleTimeout,
            requestTimeout = requestTimeout,
            handleInternalRedirect = handleInternalRedirect,
            retryPolicy = retryPolicy,
            methods = methods
        )
    )

    fun withDomain(
        url: String,
        idleTimeout: String? = null,
        connectionIdleTimeout: String? = null,
        requestTimeout: String? = null
    ) = dependencies.add(
        Dependency(
            domain = url,
            idleTimeout = idleTimeout,
            connectionIdleTimeout = connectionIdleTimeout,
            requestTimeout = requestTimeout
        )
    )

    fun withDomainPattern(
        pattern: String,
        idleTimeout: String? = null,
        connectionIdleTimeout: String? = null,
        requestTimeout: String? = null
    ) = dependencies.add(
        Dependency(
            domainPattern = pattern,
            idleTimeout = idleTimeout,
            connectionIdleTimeout = connectionIdleTimeout,
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
                        domainPattern = it.domainPattern,
                        idleTimeout = it.idleTimeout,
                        connectionIdleTimeout = it.connectionIdleTimeout,
                        requestTimeout = it.requestTimeout,
                        handleInternalRedirect = it.handleInternalRedirect,
                        retryPolicy = it.retryPolicy,
                        methods = it.methods
                    )
                )
            }
        })
    }
}

fun outgoingDependencyProto(
    service: String? = null,
    domain: String? = null,
    domainPattern: String? = null,
    handleInternalRedirect: Boolean? = null,
    idleTimeout: String? = null,
    connectionIdleTimeout: String? = null,
    requestTimeout: String? = null,
    retryPolicy: RetryPolicyInput? = null,
    methods: Set<String>? = null
) = struct {
    service?.also { putFields("service", string(service)) }
    domain?.also { putFields("domain", string(domain)) }
    retryPolicy?.also { putFields("retryPolicy", retryPolicyProto(retryPolicy)) }
    domainPattern?.also { putFields("domainPattern", string(domainPattern)) }
    handleInternalRedirect?.also { putFields("handleInternalRedirect", boolean(handleInternalRedirect)) }
    methods?.also {
        putFields("methods", list { methods.forEach { singleMethod -> addValues(string(singleMethod)) } })
    }
    if (idleTimeout != null || requestTimeout != null || connectionIdleTimeout != null) {
        putFields("timeoutPolicy", outgoingTimeoutPolicy(idleTimeout, connectionIdleTimeout, requestTimeout))
    }
}

private fun retryPolicyProto(retryPolicy: RetryPolicyInput) = struct {
    retryPolicy.retryOn?.also { putFields("retryOn", string(it)) }
    retryPolicy.hostSelectionRetryMaxAttempts?.also { putFields("hostSelectionRetryMaxAttempts", integer(it)) }
    retryPolicy.numberRetries?.also { putFields("numberRetries", integer(it)) }
    retryPolicy.retryHostPredicate?.also { putFields("retryHostPredicate", retryHostPredicateListProto(it)) }
    retryPolicy.perTryTimeoutMs?.also { putFields("perTryTimeoutMs", integer(it)) }
    retryPolicy.retryBackOff?.also { putFields("retryBackOff", retryBackOffProto(it)) }
    retryPolicy.retryableStatusCodes?.also { putFields("retryableStatusCodes", retryableStatusCodesProto(it)) }
    retryPolicy.retryableHeaders?.also { putFields("retryableHeaders", retryableHeadersProto(it)) }
}

private fun retryableHeadersProto(retryableHeaders: List<String>) = list {
    retryableHeaders.forEach { addValues(string(it)) }
}

private fun retryableStatusCodesProto(retryableStatusCodes: List<Int>) = list {
    retryableStatusCodes.forEach { addValues(integer(it)) }
}

private fun retryBackOffProto(retryBackOff: RetryBackOffInput) = struct {
    retryBackOff.baseInterval?.also { putFields("baseInterval", string(it)) }
    retryBackOff.maxInterval?.also { putFields("maxInterval", string(it)) }
}

private fun retryHostPredicateListProto(retryHostPredicateList: List<RetryHostPredicateInput>) = list {
    retryHostPredicateList.forEach {
        addValues(it.name?.let { element -> struct { putFields("name", string(element)) } })
    }
}

fun outgoingTimeoutPolicy(
    idleTimeout: String? = null,
    connectionIdleTimeout: String? = null,
    requestTimeout: String? = null
) = struct {
    idleTimeout?.also { putFields("idleTimeout", string(idleTimeout)) }
    connectionIdleTimeout?.also { putFields("connectionIdleTimeout", string(connectionIdleTimeout)) }
    requestTimeout?.also { putFields("requestTimeout", string(requestTimeout)) }
}

fun incomingEndpointProto(
    path: String? = null,
    pathPrefix: String? = null,
    pathRegex: String? = null,
    includeNullFields: Boolean = false,
    clients: List<String> = listOf("client1"),
    oauth: OAuthTestDependencies? = null
): Value = struct {

    this.putPathFields(path, "path", includeNullFields)
    this.putPathFields(pathPrefix, "pathPrefix", includeNullFields)
    this.putPathFields(pathRegex, "pathRegex", includeNullFields)

    putFields("clients", list { clients.forEach { addValues(string(it)) } })
    oauth?.let { putFields("oauth", putOauthFields(it)) }
}

fun incomingRateLimitEndpointProto(
    path: String? = null,
    pathPrefix: String? = null,
    pathRegex: String? = null,
    clients: List<String> = emptyList(),
    methods: List<String> = emptyList(),
    rateLimit: String = "0/s"
): Value = struct {

    this.putPathFields(path, "path", false)
    this.putPathFields(pathPrefix, "pathPrefix", false)
    this.putPathFields(pathRegex, "pathRegex", false)

    putFields("clients", list { clients.forEach { addValues(string(it)) } })
    putFields("methods", list { methods.forEach { addValues(string(it)) } })
    putFields("rateLimit", string(rateLimit))
}

fun Struct.Builder.putPathFields(path: String?, fieldName: String, includeNullFields: Boolean) {
    when {
        path != null -> string(path)
        includeNullFields -> nullValue
        else -> null
    }?.also {
        this.putFields(fieldName, it)
    }
}

class OAuthTestDependencies(
    val provider: String?,
    val verification: String?,
    val policy: String?
)

fun putOauthFields(oauthDependencies: OAuthTestDependencies?) = struct {
    oauthDependencies?.provider?.let {
        putFields("provider", string(it))
    }
    oauthDependencies?.verification?.let {
        putFields("verification", string(it))
    }
    oauthDependencies?.policy?.let {
        putFields("policy", string(it))
    }
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

private fun integer(value: Int): Value {
    return Value.newBuilder().setStringValue(value.toString()).build()
}

private val nullValue: Value = Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build()
