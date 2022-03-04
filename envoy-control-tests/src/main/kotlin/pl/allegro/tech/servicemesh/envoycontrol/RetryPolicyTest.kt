package pl.allegro.tech.servicemesh.envoycontrol

import okhttp3.Headers.Companion.toHeaders
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isFrom
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isOk
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.Xds
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension
import java.time.Duration

class RetryPolicyTest {
    companion object {

        private val RETRY_SETTINGS_CONFIG = """
node:
  metadata:
    proxy_settings:
      outgoing:
        dependencies:
          - service: "echo"
            retryPolicy:
              retryOn: ["retriable-status-codes"]
              numberRetries: 8
              retryableStatusCodes: [200]
          - service: "macho"
            retryPolicy:
              retryOn: ["retriable-status-codes"]
              numberRetries: 8
              retryableStatusCodes: [200]
              methods: ["PUT", "POST"]
            """.trimIndent()

        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(
            consul, mapOf(
                "envoy-control.envoy.snapshot.local-service.retry-policy.per-http-method.GET.enabled" to true,
                "envoy-control.envoy.snapshot.local-service.retry-policy.per-http-method.GET.retry-on" to listOf(
                    "connect-failure",
                    "reset"
                ),
                "envoy-control.envoy.snapshot.local-service.retry-policy.per-http-method.GET.num-retries" to 3
            )
        )

        @JvmField
        @RegisterExtension
        val service = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val envoy = EnvoyExtension(envoyControl, service, config = Xds.copy(configOverride = RETRY_SETTINGS_CONFIG))
    }

    @Test
    fun `should retry request 3 times when application is down`() {
        // given
        service.container().stop()

        // when
        envoy.ingressOperations.callLocalService(
            endpoint = "/endpoint", headers = mapOf("x-service-name" to "authorizedClient").toHeaders()
        )

        untilAsserted {
            // then
            assertThat(
                hasRetriedRequest(
                    numberOfRetries = 3,
                    metricName = "cluster.local_service.upstream_rq_retry"
                )
            ).isTrue()
        }
    }

    @Test
    fun `should retry 8 times when configured retry policy route for service`() {
        // given
        service.container().start()

        // when
        consul.server.operations.registerService(service, name = "echo")
        untilAsserted(wait = Duration.ofSeconds(10)) {
            // then
            val response = envoy.egressOperations.callService("echo")
            assertThat(response).isOk().isFrom(service)
        }

        untilAsserted(wait = Duration.ofSeconds(5)) {
            // then
            assertThat(hasRetriedRequest(numberOfRetries = 8, metricName = "cluster.echo.upstream_rq_retry")).isTrue()
        }
    }

    @Test
    fun `should have no retries for get method if post and put specified only`() {
        // given
        service.container().start()

        // when
        consul.server.operations.registerService(service, name = "macho")
        untilAsserted(wait = Duration.ofSeconds(10)) {
            // then
            val response = envoy.egressOperations.callService("macho")
            assertThat(response).isOk().isFrom(service)
        }

        untilAsserted(wait = Duration.ofSeconds(5)) {
            // then
            assertThat(hasRetriedRequest(numberOfRetries = 0, metricName = "cluster.macho.upstream_rq_retry")).isTrue()
        }
    }

    private fun hasRetriedRequest(numberOfRetries: Long, metricName: String): Boolean {
        return envoy.container.admin()
            .statValue(metricName)
            ?.toLong()
            ?.equals(numberOfRetries)
            ?: false
    }
}
