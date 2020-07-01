package pl.allegro.tech.servicemesh.envoycontrol.asssertions

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.ObjectAssert
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyContainer

class EnvoyAssertions(
    val requestBlocked: Boolean,
    val protocol: String,
    val path: String,
    val method: String,
    val clientName: String,
    val clientIp: String
)

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
        EnvoyAssertions(
            requestBlocked = true,
            protocol = protocol,
            path = path,
            method = method,
            clientName = clientName,
            clientIp = clientIp
        )
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


    // TODO(awawrzyniak) I have removed assertion hasSize(1)
    assertThat(it.logRecorder.getRecordedLogs()).first().matchesRbacAccessDeniedLog(
        EnvoyAssertions(
            requestBlocked = false,
            protocol = protocol,
            path = path,
            method = method,
            clientName = clientName,
            clientIp = clientIp
        )
    )
}

fun ObjectAssert<String>.matchesRbacAccessDeniedLog(i: EnvoyAssertions) = satisfies {
    // TODO(mfalkowski): check if this log shows properly in Kibana (correct logger, etc.)

    // val actionMessage = if (i.requestBlocked) "block" else "allow and log"
    // TODO(awawrzyniak) there is no way to catch if it's "block" or "allow and log")

    assertThat(it).isEqualTo(
        "Access denied for request: method = ${i.method}, path = ${i.path}, clientIp = ${i.clientIp}, " +
            "clientName = ${i.clientName}, protocol = ${i.protocol}\n"
    // "clientName = ${i.clientName}, protocol = ${i.protocol}. Action: $actionMessage"
    )
}
