package pl.allegro.tech.servicemesh.envoycontrol.assertions

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.ObjectAssert
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyContainer

private class RbacLogDescription(
    val protocol: String,
    val path: String,
    val method: String,
    val clientName: String,
    val clientIp: String,
    val statusCode: String
)

private const val RBAC_LOG_PREFIX = "INCOMING_PERMISSIONS"

fun isRbacAccessLog(log: String) = log.startsWith(RBAC_LOG_PREFIX)

fun ObjectAssert<EnvoyContainer>.hasNoRBACDenials(): ObjectAssert<EnvoyContainer> = satisfies {
    val admin = it.admin()
    assertThat(admin.statValue("http.ingress_http.rbac.denied")?.toInt()).isZero()
    assertThat(admin.statValue("http.ingress_https.rbac.denied")?.toInt()).isZero()
    assertThat(admin.statValue("http.ingress_http.rbac.shadow_denied")?.toInt()).isZero()
    assertThat(admin.statValue("http.ingress_https.rbac.shadow_denied")?.toInt()).isZero()

    assertThat(it.logRecorder.getRecordedLogs()).filteredOn(::isRbacAccessLog).isEmpty()
}

fun ObjectAssert<EnvoyContainer>.hasOneAccessDenialWithActionBlock(
    protocol: String,
    path: String,
    method: String,
    clientName: String,
    clientIp: String
): ObjectAssert<EnvoyContainer> = hasOneAccessDenial(
    requestBlocked = true,
    protocol = protocol,
    logDescription = RbacLogDescription(
        protocol = protocol,
        path = path,
        method = method,
        clientName = clientName,
        clientIp = clientIp,
        statusCode = "403"
    )
)

fun ObjectAssert<EnvoyContainer>.hasOneAccessDenialWithActionLog(
    protocol: String,
    path: String,
    method: String,
    clientName: String,
    clientIp: String
): ObjectAssert<EnvoyContainer> = hasOneAccessDenial(
    requestBlocked = false,
    protocol = protocol,
    logDescription = RbacLogDescription(
        protocol = protocol,
        path = path,
        method = method,
        clientIp = clientIp,
        statusCode = "200",
        clientName = clientName
    )
)

private fun ObjectAssert<EnvoyContainer>.hasOneAccessDenial(
    requestBlocked: Boolean,
    protocol: String,
    logDescription: RbacLogDescription
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
        .matchesRbacAccessDeniedLog(logDescription)
}

private fun ObjectAssert<String>.matchesRbacAccessDeniedLog(logDescription: RbacLogDescription) = satisfies {
    val logLine = "$RBAC_LOG_PREFIX { " +
            "\"method\": \"${logDescription.method}\", " +
            "\"path\": \"${logDescription.path}\", " +
            "\"clientIp\": \"${logDescription.clientIp}\", " +
            "\"clientName\": \"${logDescription.clientName}\", " +
            "\"protocol\": \"${logDescription.protocol}\", " +
            "\"statusCode\": ${logDescription.statusCode} }\n"
    assertThat(it).isEqualTo(logLine)
}
