package pl.allegro.tech.servicemesh.envoycontrol

import okhttp3.Headers.Companion.headersOf
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.Ads
import pl.allegro.tech.servicemesh.envoycontrol.config.Xds
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.GenericServiceExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.HttpContainer
import pl.allegro.tech.servicemesh.envoycontrol.config.service.RedisBasedRateLimitContainer
import pl.allegro.tech.servicemesh.envoycontrol.config.service.RedisContainer
import pl.allegro.tech.servicemesh.envoycontrol.config.service.ServiceExtension
import java.time.Duration

open class AdsRateLimitTest : RateLimitTest {
    companion object {

        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(consul)

        @JvmField
        @RegisterExtension
        val service = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val envoy = EnvoyExtension(envoyControl, service, config = Ads.copy(configOverride = RATE_LIMIT_CONFIG))

        @JvmField
        @RegisterExtension
        val redis = GenericServiceExtension(RedisContainer())

        @JvmField
        @RegisterExtension
        val rateLimitService = GenericServiceExtension(RedisBasedRateLimitContainer(redis.container()))
    }

    override fun consul() = consul

    override fun envoyControl() = envoyControl

    override fun service() = service

    override fun envoy() = envoy

    override fun rateLimitService() = rateLimitService
}

open class XdsRateLimitTest : RateLimitTest {
    companion object {
        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(consul)

        @JvmField
        @RegisterExtension
        val service = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val envoy = EnvoyExtension(envoyControl, service, config = Xds.copy(configOverride = RATE_LIMIT_CONFIG))

        @JvmField
        @RegisterExtension
        val redis = GenericServiceExtension(RedisContainer())

        @JvmField
        @RegisterExtension
        val rateLimitService = GenericServiceExtension(RedisBasedRateLimitContainer(redis.container()))
    }

    override fun consul() = consul

    override fun envoyControl() = envoyControl

    override fun service() = service

    override fun envoy() = envoy

    override fun rateLimitService() = rateLimitService
}

val RATE_LIMIT_CONFIG = """
              node:
                metadata:
                  proxy_settings:
                    incoming:
                      rateLimitEndpoints:
                        - path: /banned
                          rateLimit: 0/s
                        - pathPrefix: /limited-5-h
                          rateLimit: 5/h
                        - pathRegex: /one/.*/three
                          rateLimit: 25/m
                        - path: /limited-30-m
                          rateLimit: 30/m
                        - path: /limited-15-s
                          rateLimit: 15/s
                        - path: /banned-for-harry-potter
                          clients: ["harry-potter"]
                          rateLimit: 0/s
                        - path: /banned-for-all
                          clients: ["*"]
                          rateLimit: 0/s
            """.trimIndent()

interface RateLimitTest {

    fun consul(): ConsulExtension

    fun envoyControl(): EnvoyControlExtension

    fun service(): EchoServiceExtension

    fun envoy(): EnvoyExtension

    fun rateLimitService(): ServiceExtension<out HttpContainer>

    @Test
    fun `should limit using rateLimit`() {
        // given
        val extension = rateLimitService()
        consul().server.operations.registerService(extension = extension,
            name = "ratelimit-grpc",
            tags = listOf("envoy"), //this turns on HTTP2
            beforeRegistration = { service ->
                service.check.http = "http://${extension.container().ipAddress()}:${extension.container().httpPort()}/healthcheck"
                service.check.interval = "3s"
            }
        )

        // when
        // await propagation from consul to envoy
        awaitUntilLimited("/banned", poll = Duration.ofMillis(500))

        // then
        awaitMaxSecond(55)
        awaitUntilLimited("/limited-30-m") { counter ->
            assertThat(counter).isEqualTo(31)
        }

        // and
        awaitBeginningOfASecond()
        awaitUntilLimited("/limited-15-s") { counter ->
            assertThat(counter).isEqualTo(16)
        }

        // and
        awaitBeginningOfNextSecond()
        awaitUntilLimited("/limited-15-s") { counter ->
            assertThat(counter).isEqualTo(16)
        }

        // and
        awaitBeginningOfNextSecond()
        awaitUntilLimited("/one/two/three") { counter ->
            assertThat(counter).isEqualTo(26)
        }

        // and
        awaitUntilLimited("/banned-for-harry-potter", "harry-potter") { counter ->
            assertThat(counter).isEqualTo(1)
        }

        // and
        awaitUntilLimited("/banned-for-all", "darth-vader") { counter ->
            assertThat(counter).isEqualTo(1)
        }
    }

    private fun awaitBeginningOfASecond() {
        untilAsserted(poll = Duration.ofMillis(1)) {
            val currentMillis = (System.currentTimeMillis() % 1_000)
            assertThat(currentMillis).isLessThanOrEqualTo(10)
        }
    }

    private fun awaitBeginningOfNextSecond() {
        val nextSecondStart = ((System.currentTimeMillis() / 1000) + 1)*1000
        untilAsserted(poll = Duration.ofMillis(1)) {
            assertThat(System.currentTimeMillis()).isGreaterThanOrEqualTo(nextSecondStart)
        }
    }

    private fun awaitMaxSecond(maxSecond: Int) {
        untilAsserted(poll = Duration.ofMillis(100)) {
            val currentSecond = (System.currentTimeMillis() % 60_000) / 1000
            assertThat(currentSecond).isLessThanOrEqualTo(maxSecond.toLong())
        }
    }

    private fun awaitUntilLimited(endpoint: String, clientServiceName: String? = null, poll: Duration = Duration.ofMillis(0), resultFn: (Int) -> Unit = {}) {
        var counter = 0
        untilAsserted(poll = poll) {
            counter++
            val response = envoy().ingressOperations.callLocalService(endpoint,
                clientServiceName?.let { headersOf("x-service-name", clientServiceName)} ?: headersOf())

            assertThat(response.code).isEqualTo(429)
        }

        resultFn.invoke(counter)
    }
}
