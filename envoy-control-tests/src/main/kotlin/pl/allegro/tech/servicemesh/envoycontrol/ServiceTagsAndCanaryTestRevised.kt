package pl.allegro.tech.servicemesh.envoycontrol

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.ObjectAssert
import org.awaitility.Awaitility
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.RegisterExtension
import org.testcontainers.containers.Network
import pl.allegro.tech.servicemesh.envoycontrol.config.Ads
import pl.allegro.tech.servicemesh.envoycontrol.config.BaseEnvoyTest
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlRunnerTestApp
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestApp
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulOperations
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulServerConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulSetup
import pl.allegro.tech.servicemesh.envoycontrol.config.containers.EchoContainer
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyContainer
import java.time.Duration
import java.util.concurrent.TimeUnit

class EchoServiceExtension : BeforeEachCallback, AfterEachCallback {

    val container = EchoContainer()

    override fun beforeEach(context: ExtensionContext) {
        container.start()
    }

    override fun afterEach(context: ExtensionContext) {
        container.stop()
    }

}

class ConsulExtension : BeforeEachCallback, AfterEachCallback {

    var server: ConsulSetup? = null

    override fun beforeEach(context: ExtensionContext) {
        server = ConsulSetup(Network.SHARED, ConsulServerConfig(1, "dc1", expectNodes = 1))
        server?.container?.start()
//        server?.container?.execInContainer()
    }

    override fun afterEach(context: ExtensionContext) {
        server?.container?.stop()
        server = null
    }

}

class EnvoyControlExtension(val consul: ConsulExtension,
                            val properties: Map<String, Any> = mapOf()) : BeforeEachCallback,
        AfterEachCallback {

    var ec: EnvoyControlTestApp? = null

    override fun beforeEach(context: ExtensionContext) {
        ec = EnvoyControlRunnerTestApp(
                properties = properties,
                consulPort = consul.server?.port!!
        )
        ec?.run()
        Awaitility.await().atMost(30, TimeUnit.SECONDS).untilAsserted {
            assertThat(ec?.isHealthy()).isTrue()
        }
    }

    override fun afterEach(context: ExtensionContext) {
        ec?.stop()
        ec = null
    }

}

class EnvoyExtension(val envoyControl: EnvoyControlExtension) : BeforeEachCallback, AfterEachCallback {

    var envoy: EnvoyContainer? = null

    override fun beforeEach(context: ExtensionContext?) {
        envoy = EnvoyContainer(
                Ads,
                "127.0.0.1",
                envoyControl.ec?.grpcPort!!,
                image = EnvoyControlTestConfiguration.defaultEnvoyImage
        ).withNetwork(Network.SHARED)

        try {
            envoy?.start()
        } catch (e: Exception) {
            ServiceTagsAndCanaryTestRevised.logger.error("Logs from failed container: ${envoy?.logs}")
            throw e
        }
    }

    override fun afterEach(context: ExtensionContext?) {
        envoy?.stop()
        envoy = null
    }

}

open class ServiceTagsAndCanaryTestRevised {

    companion object {
        val logger by logger()
    }

    @JvmField
    @Order(0)
    @RegisterExtension
    val consul = ConsulExtension()

    @JvmField
    @Order(1)
    @RegisterExtension
    val envoyControl = EnvoyControlExtension(consul, mapOf(
        "envoy-control.envoy.snapshot.routing.service-tags.enabled" to true,
        "envoy-control.envoy.snapshot.routing.service-tags.metadata-key" to "tag",
        "envoy-control.envoy.snapshot.load-balancing.canary.enabled" to true,
        "envoy-control.envoy.snapshot.load-balancing.canary.metadata-key" to "canary",
        "envoy-control.envoy.snapshot.load-balancing.canary.metadata-value" to "1"
    ))

    @JvmField
    @RegisterExtension
    val envoy = EnvoyExtension(envoyControl)

    @JvmField
    @RegisterExtension
    val loremRegularService = EchoServiceExtension()

    @JvmField
    @RegisterExtension
    val loremCanaryService = EchoServiceExtension()

    @JvmField
    @RegisterExtension
    val ipsumRegularService = EchoServiceExtension()

    protected fun registerServices() {
        val consulOps = ConsulOperations(consul.server?.port!!)
        consulOps.registerService(name = "echo", address = loremRegularService.container.ipAddress(), port = EchoContainer.PORT, tags = listOf("lorem"))
        consulOps.registerService(name = "echo", address = loremCanaryService.container.ipAddress(), port = EchoContainer.PORT, tags = listOf("lorem", "canary"))
        consulOps.registerService(name = "echo", address = ipsumRegularService.container.ipAddress(), port = EchoContainer.PORT, tags = listOf("ipsum"))
    }

    fun <T> untilAsserted(wait: org.awaitility.Duration = BaseEnvoyTest.defaultDuration, fn: () -> (T)): T {
        var lastResult: T? = null
        Awaitility.await().atMost(wait).untilAsserted({ lastResult = fn() })
        assertThat(lastResult).isNotNull
        return lastResult!!
    }

    fun waitForReadyServicesRevised(vararg serviceNames: String) {
        serviceNames.forEach {
            untilAsserted {
                callService(service = it, address = envoy.envoy?.egressListenerUrl()!!).also {
                    assertThat(it).isOk()
                }
            }
        }
    }

    @Test
    fun `should route requests to canary instance with tag lorem`() {
        // given
        registerServices()
        waitForReadyServicesRevised("echo")

        // when
        val stats = callEchoServiceRepeatedly(repeat = 10, tag = "lorem", canary = true)

        // then
        assertThat(stats.totalHits).isEqualTo(10)
        assertThat(stats.loremCanaryHits).isEqualTo(10)
        assertThat(stats.loremRegularHits).isEqualTo(0)
        assertThat(stats.ipsumRegularHits).isEqualTo(0)
    }

    @Test
    fun `should fallback to regular instance with tag ipsum if canary instance doesn't exist`() {
        // given
        registerServices()
        waitForReadyServicesRevised("echo")

        // when
        val stats = callEchoServiceRepeatedly(repeat = 10, tag = "ipsum", canary = true)

        // then
        assertThat(stats.totalHits).isEqualTo(10)
        assertThat(stats.loremCanaryHits).isEqualTo(0)
        assertThat(stats.loremRegularHits).isEqualTo(0)
        assertThat(stats.ipsumRegularHits).isEqualTo(10)
    }

    @Test
    fun `should route requests to canary instance`() {
        // given
        registerServices()
        waitForReadyServicesRevised("echo")

        // when
        val stats = callEchoServiceRepeatedly(repeat = 10, canary = true)

        // then
        assertThat(stats.totalHits).isEqualTo(10)
        assertThat(stats.loremCanaryHits).isEqualTo(10)
        assertThat(stats.loremRegularHits).isEqualTo(0)
        assertThat(stats.ipsumRegularHits).isEqualTo(0)
    }

    @Test
    fun `should return 503 if no instance with requested tag is found`() {
        // given
        registerServices()
        waitForReadyServicesRevised("echo")

        // when
        val stats = callEchoServiceRepeatedly(repeat = 10, tag = "not-found", canary = true, assertNoErrors = false)

        // then
        assertThat(stats.totalHits).isEqualTo(10)
        assertThat(stats.failedHits).isEqualTo(10)
        assertThat(stats.loremCanaryHits).isEqualTo(0)
        assertThat(stats.loremRegularHits).isEqualTo(0)
        assertThat(stats.ipsumRegularHits).isEqualTo(0)
    }

    protected open fun callStats() = EnvoyControlTestConfiguration.CallStats(
            listOf(loremCanaryService.container, loremRegularService.container,
                    ipsumRegularService.container))

    protected open fun callEchoServiceRepeatedly(
        repeat: Int,
        tag: String? = null,
        canary: Boolean,
        assertNoErrors: Boolean = true
    ): EnvoyControlTestConfiguration.CallStats {
        val stats = callStats()
        val tagHeader = tag?.let { mapOf("x-service-tag" to it) } ?: emptyMap()
        val canaryHeader = if (canary) mapOf("x-canary" to "1") else emptyMap()

        callServiceRepeatedly(
            service = "echo",
            stats = stats,
            minRepeat = repeat,
            maxRepeat = repeat,
            headers = tagHeader + canaryHeader,
            assertNoErrors = assertNoErrors,
            address = envoy.envoy?.egressListenerUrl()!!
        )
        return stats
    }

    val EnvoyControlTestConfiguration.CallStats.loremCanaryHits: Int
        get() = this.hits(loremCanaryService.container)
    val EnvoyControlTestConfiguration.CallStats.loremRegularHits: Int
        get() = this.hits(loremRegularService.container)
    val EnvoyControlTestConfiguration.CallStats.ipsumRegularHits: Int
        get() = this.hits(ipsumRegularService.container)

    fun callService(
            service: String,
            address: String = EnvoyControlTestConfiguration.envoyContainer1.egressListenerUrl(),
            headers: Map<String, String> = mapOf(),
            pathAndQuery: String = ""
    ): Response = call(service, address, headers, pathAndQuery)

    private fun call(
            host: String,
            address: String = EnvoyControlTestConfiguration.envoyContainer1.egressListenerUrl(),
            headers: Map<String, String> = mapOf(),
            pathAndQuery: String = "",
            client: OkHttpClient = OkHttpClient.Builder()
                    // envoys default timeout is 15 seconds while OkHttp is 10
                    .readTimeout(Duration.ofSeconds(20))
                    .build()

    ): Response {
        val request = client.newCall(
                Request.Builder()
                        .get()
                        .header("Host", host)
                        .apply {
                            headers.forEach { name, value -> header(name, value) }
                        }
                        .url(HttpUrl.get(address).newBuilder(pathAndQuery)!!.build())
                        .build()
        )

        return request.execute()
    }

    protected fun callServiceRepeatedly(
            service: String,
            stats: EnvoyControlTestConfiguration.CallStats,
            minRepeat: Int = 1,
            maxRepeat: Int = 100,
            repeatUntil: (EnvoyControlTestConfiguration.ResponseWithBody) -> Boolean = { false },
            headers: Map<String, String> = mapOf(),
            pathAndQuery: String = "",
            assertNoErrors: Boolean = true,
            address: String = EnvoyControlTestConfiguration.envoyContainer1.egressListenerUrl()
    ): EnvoyControlTestConfiguration.CallStats {
        var conditionFulfilled = false
        (1..maxRepeat).asSequence()
                .map { i ->
                    EnvoyControlTestConfiguration.callService(service = service, headers = headers,
                            pathAndQuery = pathAndQuery, address = address).also {
                        if (assertNoErrors) assertThat(it).isOk().describedAs("Error response at attempt $i: \n$it")
                    }
                }
                .map { EnvoyControlTestConfiguration.ResponseWithBody(it, it.body()?.string() ?: "") }
                .onEach { conditionFulfilled = conditionFulfilled || repeatUntil(it) }
                .withIndex()
                .takeWhile { (i, _) -> i < minRepeat || !conditionFulfilled }
                .map { it.value }
                .forEach { stats.addResponse(it) }
        return stats
    }

    fun ObjectAssert<Response>.isOk(): ObjectAssert<Response> {
        matches { it.isSuccessful }
        return this
    }

}
