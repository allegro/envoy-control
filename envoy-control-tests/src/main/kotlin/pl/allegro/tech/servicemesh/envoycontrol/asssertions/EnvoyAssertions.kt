package pl.allegro.tech.servicemesh.envoycontrol.asssertions

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.ObjectAssert
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyContainer

class EnvoyAssertions(
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

    val deniedRules = admin.statValue("http.ingress_$protocol.rbac.denied")?.toInt()
    val deniedShadowRules = admin.statValue("http.ingress_$protocol.rbac.shadow_denied")?.toInt()

    assertThat(deniedRules).isOne()
    assertThat(deniedShadowRules).isOne()

    assertThat(it.logRecorder.getRecordedLogs()).hasSize(1).first()
        .matchesRbacAccessDeniedLog(
            EnvoyAssertions(
                protocol = protocol,
                path = path,
                method = method,
                clientName = clientName,
                clientIp = clientIp
            ),
            statusCode = "403")
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
        EnvoyAssertions(
            protocol = protocol,
            path = path,
            method = method,
            clientName = clientName,
            clientIp = clientIp
        ),
        statusCode = "200"
    )
}

fun ObjectAssert<String>.matchesRbacAccessDeniedLog(envoyAssertions: EnvoyAssertions, statusCode: String) = satisfies {
    // TODO(mfalkowski): check if this log shows properly in Kibana (correct logger, etc.)

    assertThat(it).isEqualTo(
        "Access denied for request: method = ${envoyAssertions.method}, path = ${envoyAssertions.path}, " +
            "clientIp = ${envoyAssertions.clientIp}, clientName = ${envoyAssertions.clientName}, " +
            "protocol = ${envoyAssertions.protocol}, statusCode = ${statusCode}\n"
    )
}
