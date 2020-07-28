package pl.allegro.tech.servicemesh.envoycontrol

import okhttp3.Headers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension

class RetryPolicyTest {
    companion object {

        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(consul, mapOf(
                "envoy-control.envoy.snapshot.local-service.retry-policy.per-http-method.GET.enabled" to true,
                "envoy-control.envoy.snapshot.local-service.retry-policy.per-http-method.GET.retry-on" to listOf("connect-failure", "reset"),
                "envoy-control.envoy.snapshot.local-service.retry-policy.per-http-method.GET.num-retries" to 3
        ))

        @JvmField
        @RegisterExtension
        val service = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val envoy = EnvoyExtension(envoyControl, service)
    }

    @Test
    fun `should retry request 3 times when application is down`() {
        // given
        service.container.stop()

        // when
        envoy.ingressOperations.callLocalService(
                endpoint = "/endpoint", headers = Headers.of(mapOf("x-service-name" to "authorizedClient"))
        )

        untilAsserted {
            // then
            assertThat(hasRetriedRequest(numberOfRetries = 3)).isTrue()
        }
    }

    private fun hasRetriedRequest(numberOfRetries: Long): Boolean {
        return envoy.container.admin()
            .statValue("cluster.local_service.upstream_rq_retry")
            ?.toLong()
            ?.equals(numberOfRetries)
            ?: false
    }
}
