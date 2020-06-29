package pl.allegro.tech.servicemesh.envoycontrol.asssertions

import org.assertj.core.api.Assertions
import org.assertj.core.api.ObjectAssert
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyContainer

fun ObjectAssert<EnvoyContainer>.hasNoRBACDenials() = satisfies {
    val admin = it.admin()
    Assertions.assertThat(admin.statValue("http.ingress_http.rbac.denied")?.toInt()).isZero()
    Assertions.assertThat(admin.statValue("http.ingress_https.rbac.denied")?.toInt()).isZero()
    Assertions.assertThat(admin.statValue("http.ingress_http.rbac.shadow_denied")?.toInt()).isZero()
    Assertions.assertThat(admin.statValue("http.ingress_https.rbac.shadow_denied")?.toInt()).isZero()

    Assertions.assertThat(it.logRecorder.getRecordedLogs()).isEmpty()
}

fun ObjectAssert<EnvoyContainer>.hasOneAccessDenialWithActionBlock(protocol: String) = satisfies {
    val admin = it.admin()
    Assertions.assertThat(admin.statValue("http.ingress_$protocol.rbac.denied")?.toInt()).isOne()
    Assertions.assertThat(admin.statValue("http.ingress_$protocol.rbac.shadow_denied")?.toInt()).isOne()

    // TODO: check log contents
    Assertions.assertThat(it.logRecorder.getRecordedLogs()).hasSize(1)
}

fun ObjectAssert<EnvoyContainer>.hasOneAccessDenialWithActionLog(protocol: String) = satisfies {
    val admin = it.admin()
    Assertions.assertThat(admin.statValue("http.ingress_$protocol.rbac.denied")?.toInt()).isZero()
    Assertions.assertThat(admin.statValue("http.ingress_$protocol.rbac.shadow_denied")?.toInt()).isOne()

    // TODO: check log contents
    Assertions.assertThat(it.logRecorder.getRecordedLogs()).hasSize(1)
}
