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
    val authority: String? = null,
    val luaDestinationAuthority: String? = null,
    val clientAllowedToAllEndpoints: Boolean? = null,
    val clientIp: String? = null,
    val statusCode: String? = null,
    val requestId: String? = null,
    val rbacAction: String? = null,
    val jwtTokenStatus: String? = null,
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
    authority: String,
    clientIp: String,
    clientAllowedToAllEndpoints: Boolean = false
): ObjectAssert<EnvoyContainer> = hasOneAccessDenial(
    requestBlocked = true,
    protocol = protocol,
    logPredicate = RbacLog(
        protocol = protocol,
        path = path,
        method = method,
        clientName = clientName,
        trustedClient = trustedClient,
        authority = authority,
        clientIp = clientIp,
        clientAllowedToAllEndpoints = clientAllowedToAllEndpoints,
        statusCode = "403",
        rbacAction = "denied"
    )
)

fun ObjectAssert<EnvoyContainer>.hasOneAccessAllowedWithActionLog(
    protocol: String,
    path: String? = null,
    method: String? = null,
    clientName: String? = null,
    trustedClient: Boolean? = null,
    authority: String? = null,
    clientAllowedToAllEndpoints: Boolean? = null,
    clientIp: String? = null,
    requestId: String? = null
): ObjectAssert<EnvoyContainer> = hasOneAccessDenial(
    requestBlocked = false,
    protocol = protocol,
    shadowDenied = false,
    logPredicate = RbacLog(
        protocol = protocol,
        path = path,
        method = method,
        clientIp = clientIp,
        statusCode = "200",
        clientName = clientName,
        trustedClient = trustedClient,
        authority = authority,
        clientAllowedToAllEndpoints = clientAllowedToAllEndpoints,
        requestId = requestId
    )
)

fun ObjectAssert<EnvoyContainer>.hasOneAccessDenialWithActionLog(
    protocol: String,
    path: String? = null,
    method: String? = null,
    clientName: String? = null,
    trustedClient: Boolean? = null,
    authority: String? = null,
    clientAllowedToAllEndpoints: Boolean? = null,
    clientIp: String? = null,
    requestId: String? = null
): ObjectAssert<EnvoyContainer> = hasOneAccessDenial(
    requestBlocked = false,
    protocol = protocol,
    shadowDenied = false,
    logPredicate = RbacLog(
        protocol = protocol,
        path = path,
        method = method,
        clientIp = clientIp,
        statusCode = "200",
        clientName = clientName,
        authority = authority,
        trustedClient = trustedClient,
        clientAllowedToAllEndpoints = clientAllowedToAllEndpoints,
        requestId = requestId
    )
)

fun ObjectAssert<EnvoyContainer>.hasOneAccessDenialWithActionLog(
    protocol: String,
    path: String? = null,
    method: String? = null,
    clientName: String? = null,
    trustedClient: Boolean? = null,
    authority: String? = null,
    clientAllowedToAllEndpoints: Boolean? = null,
    clientIp: String? = null,
    requestId: String? = null,
    rbacAction: String? = "shadow_denied",
    statusCode: String? = "200"
): ObjectAssert<EnvoyContainer> = hasOneAccessDenial(
    requestBlocked = false,
    protocol = protocol,
    logPredicate = RbacLog(
        protocol = protocol,
        path = path,
        method = method,
        clientIp = clientIp,
        statusCode = statusCode,
        clientName = clientName,
        authority = authority,
        trustedClient = trustedClient,
        clientAllowedToAllEndpoints = clientAllowedToAllEndpoints,
        requestId = requestId,
        rbacAction = rbacAction
    )
)

private fun ObjectAssert<EnvoyContainer>.hasOneAccessDenial(
    requestBlocked: Boolean,
    protocol: String,
    logPredicate: RbacLog,
    shadowDenied: Boolean = true
) = satisfies {
    val admin = it.admin()
    val blockedRequestsCount = admin.statValue("http.ingress_$protocol.rbac.denied")?.toInt()
    val loggedRequestsCount = admin.statValue("http.ingress_$protocol.rbac.shadow_denied")?.toInt()

    if (requestBlocked) {
        assertThat(blockedRequestsCount).isOne()
    } else {
        assertThat(blockedRequestsCount).isZero()
    }
    if (shadowDenied) {
        assertThat(loggedRequestsCount).isOne()
    }

    assertThat(it.logRecorder.getRecordedLogs()).filteredOn(::isRbacAccessLog)
        .hasSize(1).first()
        .matchesRbacAccessDeniedLog(logPredicate)
}

private fun ObjectAssert<String>.matchesRbacAccessDeniedLog(logPredicate: RbacLog) = satisfies {
    val parsed = mapper.readValue<RbacLog>(it.removePrefix(RBAC_LOG_PREFIX))
    // protocol is required because we check metrics
    assertThat(parsed.protocol).isEqualTo(logPredicate.protocol)
    assertEqualProperty(parsed, logPredicate, RbacLog::protocol)
    assertEqualProperty(parsed, logPredicate, RbacLog::method)
    assertEqualProperty(parsed, logPredicate, RbacLog::path)
    assertEqualProperty(parsed, logPredicate, RbacLog::clientIp)
    assertEqualProperty(parsed, logPredicate, RbacLog::clientName)
    assertEqualProperty(parsed, logPredicate, RbacLog::trustedClient)
    assertEqualProperty(parsed, logPredicate, RbacLog::authority)
    assertEqualProperty(parsed, logPredicate, RbacLog::luaDestinationAuthority)
    assertEqualProperty(parsed, logPredicate, RbacLog::clientAllowedToAllEndpoints)
    assertEqualProperty(parsed, logPredicate, RbacLog::statusCode)
    assertEqualProperty(parsed, logPredicate, RbacLog::requestId)
    assertEqualProperty(parsed, logPredicate, RbacLog::rbacAction)
    assertEqualProperty(parsed, logPredicate, RbacLog::statusCode)
    assertEqualProperty(parsed, logPredicate, RbacLog::jwtTokenStatus)
}

private fun <T> assertEqualProperty(actual: RbacLog, expected: RbacLog, supplier: RbacLog.() -> T) {
    expected.supplier()?.let {
        assertThat(actual.supplier()).isEqualTo(expected.supplier())
    }
}
