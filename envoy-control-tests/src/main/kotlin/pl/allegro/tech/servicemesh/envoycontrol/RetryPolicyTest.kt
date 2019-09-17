package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlRunnerTestApp
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration

internal class RetryPolicyTest : EnvoyControlTestConfiguration() {
    companion object {

        private val properties = mapOf(
            "envoy-control.envoy.snapshot.local-service.retry-policy.per-http-method.GET.enabled" to true,
            "envoy-control.envoy.snapshot.local-service.retry-policy.per-http-method.GET.retry-on" to listOf("connect-failure", "reset"),
            "envoy-control.envoy.snapshot.local-service.retry-policy.per-http-method.GET.num-retries" to 3
        )

        @JvmStatic
        @BeforeAll
        fun setupRetryPolicyTest() {
            setup(appFactoryForEc1 = { consulPort -> EnvoyControlRunnerTestApp(properties, consulPort) })
        }
    }

    @Test
    fun `should retry request 3 times when application is down`() {
        // given
        localServiceContainer.stop()

        // when
        callLocalService(endpoint = "/endpoint", clientServiceName = "authorizedClient")

        untilAsserted {
            // then
            assertThat(hasRetriedRequest(numberOfRetries = 3)).isTrue()
        }
    }

    private fun hasRetriedRequest(numberOfRetries: Long): Boolean {
        return envoyContainer.admin()
            .statValue("cluster.local_service.upstream_rq_retry")
            ?.toLong()
            ?.equals(numberOfRetries)
            ?: false
    }
}
