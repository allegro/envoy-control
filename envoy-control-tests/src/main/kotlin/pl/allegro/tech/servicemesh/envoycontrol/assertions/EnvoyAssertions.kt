package pl.allegro.tech.servicemesh.envoycontrol.assertions

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.ObjectAssert
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyContainer

private class RbacLog(
    val protocol: String,
    val path: String? = null,
    val method: String? = null,
    val clientName: String? = null,
    val trustedClient: Boolean? = null,
    val allowedClient: Boolean? = null,
    val clientIp: String? = null,
    val statusCode: String? = null,
    val requestId: String? = null
)

private const val RBAC_LOG_PREFIX = "INCOMING_PERMISSIONS"
private val mapper = jacksonObjectMapper()

fun isRbacAccessLog(log: String) = log.startsWith(RBAC_LOG_PREFIX)

fun ObjectAssert<EnvoyContainer>.hasNoRBACDenials(): ObjectAssert<EnvoyContainer> = satisfies {
    val admin = it.admin()
    assertThat(admin.statValue("http.ingress_http.rbac.denied")?.toInt()).isZero()
    assertThat(admin.statValue("http.ingress_https.rbac.denied")?.toInt()).isZero()
    assertThat(admin.statValue("http.ingress_http.rbac.shadow_denied")?.toInt()).isZero()
    assertThat(admin.statValue("http.ingress_https.rbac.shadow_denied")?.toInt()).isZero()

    assertThat(it.logRecorder.getRecordedLogs()).filteredOn(::isRbacAccessLog).isEmpty()
}

@Suppress("LongParameterList")
fun ObjectAssert<EnvoyContainer>.hasOneAccessDenialWithActionBlock(
    protocol: String,
    path: String,
    method: String,
    clientName: String,
    trustedClient: Boolean,
    clientIp: String
): ObjectAssert<EnvoyContainer> = hasOneAccessDenial(
    requestBlocked = true,
    protocol = protocol,
    logPredicate = RbacLog(
        protocol = protocol,
        path = path,
        method = method,
        clientName = clientName,
        trustedClient = trustedClient,
        clientIp = clientIp,
        statusCode = "403"
    )
)

fun ObjectAssert<EnvoyContainer>.hasOneAccessDenialWithActionLog(
    protocol: String,
    path: String? = null,
    method: String? = null,
    clientName: String? = null,
    trustedClient: Boolean? = null,
    allowedClient: Boolean? = null,
    clientIp: String? = null,
    requestId: String? = null
): ObjectAssert<EnvoyContainer> = hasOneAccessDenial(
    requestBlocked = false,
    protocol = protocol,
    logPredicate = RbacLog(
        protocol = protocol,
        path = path,
        method = method,
        clientIp = clientIp,
        statusCode = "200",
        clientName = clientName,
        trustedClient = trustedClient,
        allowedClient = allowedClient,
        requestId = requestId
    )
)

private fun ObjectAssert<EnvoyContainer>.hasOneAccessDenial(
    requestBlocked: Boolean,
    protocol: String,
    logPredicate: RbacLog
) = satisfies {
    val admin = it.admin()
    val blockedRequestsCount = admin.statValue("http.ingress_$protocol.rbac.denied")?.toInt()
    val loggedRequestsCount = admin.statValue("http.ingress_$protocol.rbac.shadow_denied")?.toInt()

    if (requestBlocked) {
        assertThat(blockedRequestsCount).isOne()
    } else {
        assertThat(blockedRequestsCount).isZero()
    }
    assertThat(loggedRequestsCount).isOne()

    assertThat(it.logRecorder.getRecordedLogs()).filteredOn(::isRbacAccessLog)
        .hasSize(1).first()
        .matchesRbacAccessDeniedLog(logPredicate)
}

private fun ObjectAssert<String>.matchesRbacAccessDeniedLog(logPredicate: RbacLog) = satisfies {
    val parsed = mapper.readValue<RbacLog>(it.removePrefix(RBAC_LOG_PREFIX))
    // protocol is required because we check metrics
    assertThat(parsed.protocol).isEqualTo(logPredicate.protocol)

    logPredicate.method?.let {
        assertThat(parsed.method).isEqualTo(logPredicate.method)
    }
    logPredicate.path?.let {
        assertThat(parsed.path).isEqualTo(logPredicate.path)
    }
    logPredicate.clientIp?.let {
        assertThat(parsed.clientIp).isEqualTo(logPredicate.clientIp)
    }
    logPredicate.clientName?.let {
        assertThat(parsed.clientName).isEqualTo(logPredicate.clientName)
    }
    logPredicate.trustedClient?.let {
        assertThat(parsed.trustedClient).isEqualTo(logPredicate.trustedClient)
    }
    logPredicate.allowedClient?.let {
        assertThat(parsed.allowedClient).isEqualTo(logPredicate.allowedClient)
    }
    logPredicate.statusCode?.let {
        assertThat(parsed.statusCode).isEqualTo(logPredicate.statusCode)
    }
    logPredicate.requestId?.let {
        assertThat(parsed.requestId).isEqualTo(logPredicate.requestId)
    }
}
