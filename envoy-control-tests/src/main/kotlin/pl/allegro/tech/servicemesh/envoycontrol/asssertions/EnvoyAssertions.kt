package pl.allegro.tech.servicemesh.envoycontrol.asssertions

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.ObjectAssert
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyContainer

fun ObjectAssert<EnvoyContainer>.hasNoRBACDenials() = satisfies {
    val admin = it.admin()
    assertThat(admin.statValue("http.ingress_http.rbac.denied")?.toInt()).isZero()
    assertThat(admin.statValue("http.ingress_https.rbac.denied")?.toInt()).isZero()
    assertThat(admin.statValue("http.ingress_http.rbac.shadow_denied")?.toInt()).isZero()
    assertThat(admin.statValue("http.ingress_https.rbac.shadow_denied")?.toInt()).isZero()

    assertThat(it.logRecorder.getRecordedLogs()).isEmpty()
}


fun ObjectAssert<EnvoyContainer>.hasOneAccessDenialWithActionBlock(
    protocol: String,
    path: String,
    method: String,
    clientName: String,
    clientIp: String
) = satisfies {
    val admin = it.admin()
    assertThat(admin.statValue("http.ingress_$protocol.rbac.denied")?.toInt()).isOne()
    assertThat(admin.statValue("http.ingress_$protocol.rbac.shadow_denied")?.toInt()).isOne()

    assertThat(it.logRecorder.getRecordedLogs()).hasSize(1).first().matchesRbacAccessDeniedLog(
        requestBlocked = true,
        protocol = protocol,
        path = path,
        method = method,
        clientName = clientName,
        clientIp = clientIp
    )
}

fun ObjectAssert<EnvoyContainer>.hasOneAccessDenialWithActionLog(
    protocol: String,
    path: String,
    method: String,
    clientName: String,
    clientIp: String
) = satisfies {
    val admin = it.admin()
    assertThat(admin.statValue("http.ingress_$protocol.rbac.denied")?.toInt()).isZero()
    assertThat(admin.statValue("http.ingress_$protocol.rbac.shadow_denied")?.toInt()).isOne()

    assertThat(it.logRecorder.getRecordedLogs()).hasSize(1).first().matchesRbacAccessDeniedLog(
        requestBlocked = false,
        protocol = protocol,
        path = path,
        method = method,
        clientName = clientName,
        clientIp = clientIp
    )
}


fun ObjectAssert<String>.matchesRbacAccessDeniedLog(
    requestBlocked: Boolean,
    protocol: String,
    path: String,
    method: String,
    clientName: String,
    clientIp: String
) = satisfies {
    // TODO(mfalkowski): check if this log shows properly in Kibana (correct logger, etc.)
    val actionMessage = if (requestBlocked) "block" else "allow and log"

    assertThat(it).isEqualTo(
        "Access denied for request: method = $method, path = $path, clientIp = $clientIp, " +
            "clientName = $clientName, protocol = $protocol. Action: $actionMessage"
    )
}

