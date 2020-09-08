package pl.allegro.tech.servicemesh.envoycontrol.config

import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.ObjectAssert
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.springframework.boot.actuate.health.Status
import pl.allegro.tech.servicemesh.envoycontrol.config.containers.ToxiproxyContainer
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.CallStats
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EgressOperations
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyContainer
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.IngressOperations
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.ResponseWithBody
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlRunnerTestApp
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlTestApp
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.Health
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoContainer
import pl.allegro.tech.servicemesh.envoycontrol.logger
import pl.allegro.tech.servicemesh.envoycontrol.services.ServicesState
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.random.Random

data class EnvoyConfig(
    val filePath: String,
    val serviceName: String = "echo",
    val configOverride: String = "",
    val trustedCa: String = "/app/root-ca.crt",
    val certificateChain: String = "/app/fullchain_echo.pem",
    val privateKey: String = "/app/privkey.pem"
)
val AdsAllDependencies = EnvoyConfig("envoy/config_ads_all_dependencies.yaml")
val AdsCustomHealthCheck = EnvoyConfig("envoy/config_ads_custom_health_check.yaml")
val FaultyConfig = EnvoyConfig("envoy/bad_config.yaml")
val Ads = EnvoyConfig("envoy/config_ads.yaml")
val AdsV3 = EnvoyConfig("envoy/config_ads_v3.yaml")
val Echo1EnvoyAuthConfig = EnvoyConfig("envoy/config_auth.yaml")
val Echo2EnvoyAuthConfig = Echo1EnvoyAuthConfig.copy(
    serviceName = "echo2",
    certificateChain = "/app/fullchain_echo2.pem",
    privateKey = "/app/privkey_echo2.pem"
)
val Echo3EnvoyAuthConfig = Echo1EnvoyAuthConfig.copy(
    serviceName = "echo3",
    certificateChain = "/app/fullchain_echo3.pem",
    privateKey = "/app/privkey_echo3.pem"
)
val AdsWithDisabledEndpointPermissions = EnvoyConfig("envoy/config_ads_disabled_endpoint_permissions.yaml")
val AdsWithStaticListeners = EnvoyConfig("envoy/config_ads_static_listeners.yaml")
val AdsWithNoDependencies = EnvoyConfig("envoy/config_ads_no_dependencies.yaml")
val Xds = EnvoyConfig("envoy/config_xds.yaml")
val RandomConfigFile =
    EnvoyConfig(filePath = if (Random.nextBoolean()) Ads.filePath else Xds.filePath)

abstract class EnvoyControlTestConfiguration : BaseEnvoyTest() {
    companion object {
        private val logger by logger()
        private val defaultClient = ClientsFactory.createClient()
        private val insecureClient = ClientsFactory.createInsecureClient()

        lateinit var envoyContainer1: EnvoyContainer
        lateinit var envoyContainer2: EnvoyContainer
        lateinit var localServiceContainer: EchoContainer
        lateinit var envoyControl1: EnvoyControlTestApp
        lateinit var envoyControl2: EnvoyControlTestApp
        var envoyControls: Int = 1
        var envoys: Int = 1

        // We use envoy version from master. This is 1.14.0-dev.
        const val defaultEnvoyImage = "envoyproxy/envoy-alpine-dev:5b1723ff54b1a51e104c514ee6363234aaa44366"

        @JvmStatic
        fun setup(
            envoyConfig: EnvoyConfig = RandomConfigFile,
            secondEnvoyConfig: EnvoyConfig = envoyConfig,
            envoyImage: String = defaultEnvoyImage,
            appFactoryForEc1: (Int) -> EnvoyControlTestApp = defaultAppFactory(),
            appFactoryForEc2: (Int) -> EnvoyControlTestApp = appFactoryForEc1,
            envoyControls: Int = 1,
            envoys: Int = 1,
            envoyConnectGrpcPort: Int? = null,
            envoyConnectGrpcPort2: Int? = null,
            ec1RegisterPort: Int? = null,
            ec2RegisterPort: Int? = null,
            instancesInSameDc: Boolean = false
        ) {
            assertThat(envoyControls == 1 || envoyControls == 2).isTrue()
            assertThat(envoys == 1 || envoys == 2).isTrue()

            localServiceContainer = EchoContainer().also { it.start() }

            Companion.envoyControls = envoyControls

            envoyControl1 = appFactoryForEc1(consulHttpPort).also { it.run() }

            if (envoyControls == 2) {
                envoyControl2 = appFactoryForEc2(consul2HttpPort).also { it.run() }
            }

            envoyContainer1 = createEnvoyContainer(
                envoyConfig = envoyConfig,
                instancesInSameDc = instancesInSameDc,
                envoyConnectGrpcPort = envoyConnectGrpcPort,
                envoyConnectGrpcPort2 = envoyConnectGrpcPort2,
                envoyImage = envoyImage
            )

            waitForEnvoyControlsHealthy()
            registerEnvoyControls(ec1RegisterPort, ec2RegisterPort, instancesInSameDc)
            try {
                envoyContainer1.start()
            } catch (e: Exception) {
                logger.error("Logs from failed container: ${envoyContainer1.logs}")
                throw e
            }

            if (envoys == 2) {
                envoyContainer2 = createEnvoyContainer(
                    envoyConfig = secondEnvoyConfig,
                    envoyConnectGrpcPort = envoyConnectGrpcPort,
                    envoyConnectGrpcPort2 = envoyConnectGrpcPort2,
                    localServiceIp = echoContainer2.ipAddress(),
                    envoyImage = envoyImage
                )
                try {
                    envoyContainer2.start()
                } catch (e: Exception) {
                    logger.error("Logs from failed container: ${envoyContainer2.logs}")
                    throw e
                }
            }
        }

        @AfterAll
        @JvmStatic
        fun teardown() {
            envoyContainer1.stop()
            if (envoys == 2) {
                envoyContainer2.stop()
            }
            envoyControl1.stop()
            if (envoyControls == 2) {
                envoyControl2.stop()
            }
            localServiceContainer.stop()
        }

        private fun createEnvoyContainer(
            envoyConfig: EnvoyConfig,
            instancesInSameDc: Boolean = true,
            envoyConnectGrpcPort: Int? = null,
            envoyConnectGrpcPort2: Int? = null,
            localServiceIp: String = localServiceContainer.ipAddress(),
            envoyImage: String = defaultEnvoyImage
        ): EnvoyContainer {
            val envoyControl1XdsPort = envoyConnectGrpcPort ?: envoyControl1.grpcPort
            val envoyControl2XdsPort = if (envoyControls == 2 && instancesInSameDc) {
                envoyConnectGrpcPort2 ?: envoyControl2.grpcPort
            } else envoyControl1XdsPort
            return EnvoyContainer(
                config = envoyConfig,
                localServiceIp = { localServiceIp },
                envoyControl1XdsPort = envoyControl1XdsPort,
                envoyControl2XdsPort = envoyControl2XdsPort,
                image = envoyImage
            ).withNetwork(network)
        }

        fun createEnvoyContainerWithFaultyConfig(): EnvoyContainer {
            return createEnvoyContainer(
                envoyConfig = FaultyConfig,
                envoyConnectGrpcPort = null,
                envoyConnectGrpcPort2 = null
            ).withStartupTimeout(Duration.ofSeconds(10))
        }

        fun createEnvoyContainerWithEcho3Certificate(configOverride: String = ""): EnvoyContainer {
            val echo3EnvoyConfig = Echo3EnvoyAuthConfig.copy(configOverride = configOverride)

            return EnvoyContainer(
                echo3EnvoyConfig,
                { localServiceContainer.ipAddress() },
                envoyControl1.grpcPort,
                image = defaultEnvoyImage
            ).withNetwork(network)
        }

        fun registerEnvoyControls(
            ec1RegisterPort: Int?,
            ec2RegisterPort: Int?,
            instancesInSameDc: Boolean
        ) {
            registerService(
                "1",
                envoyControl1.appName,
                "localhost",
                ec1RegisterPort ?: envoyControl1.appPort,
                consulOperationsInFirstDc
            )
            if (envoyControls == 2) {
                registerService(
                    "2",
                    envoyControl2.appName,
                    "localhost",
                    ec2RegisterPort ?: envoyControl2.appPort,
                    if (instancesInSameDc) consulOperationsInFirstDc else consulOperationsInSecondDc
                )
            }
        }

        private fun waitForEnvoyControlsHealthy() {
            await().atMost(30, TimeUnit.SECONDS).untilAsserted {
                assertThat(envoyControl1.isHealthy()).isTrue()
                if (envoyControls == 2) {
                    assertThat(envoyControl2.isHealthy()).isTrue()
                }
            }
        }

        fun callEnvoyIngress(envoy: EnvoyContainer = envoyContainer1, path: String, useSsl: Boolean = false): Response =
            call(address = envoy.ingressListenerUrl(secured = useSsl), pathAndQuery = path)

        fun callIngressRoot(address: String = envoyContainer1.ingressListenerUrl()): Response =
            call(address = address)

        fun callEcho(address: String = envoyContainer1.egressListenerUrl()): Response =
            call("echo", address)

        fun callDomain(domain: String, address: String = envoyContainer1.egressListenerUrl()): Response =
            call(host = domain, address = address)

        fun callService(
            service: String,
            fromEnvoy: EnvoyContainer = envoyContainer1,
            headers: Map<String, String> = mapOf(),
            pathAndQuery: String = ""
        ): Response = EgressOperations(fromEnvoy).callService(service, headers, pathAndQuery)

        fun callServiceInsecure(
            service: String,
            address: String = envoyContainer1.egressListenerUrl(),
            headers: Map<String, String> = mapOf(),
            pathAndQuery: String = ""
        ): Response = call(service, address, headers, pathAndQuery, insecureClient)

        fun call(
            service: String,
            from: EnvoyContainer,
            path: String,
            method: String = "GET",
            body: RequestBody? = null
        ) = call(
            host = service, address = from.egressListenerUrl(), pathAndQuery = path,
            method = method, body = body, headers = mapOf()
        )

        fun call(
            host: String? = null,
            address: String = envoyContainer1.egressListenerUrl(),
            headers: Map<String, String> = mapOf(),
            pathAndQuery: String = "",
            client: OkHttpClient = defaultClient,
            method: String = "GET",
            body: RequestBody? = null
        ): Response {
            val request = client.newCall(
                Request.Builder()
                    .method(method, body)
                    .apply { if (host != null) header("Host", host) }
                    .apply {
                        headers.forEach { name, value -> header(name, value) }
                    }
                    .url(HttpUrl.get(address).newBuilder(pathAndQuery)!!.build())
                    .build()
            )

            return request.execute()
        }

        fun callLocalService(
            endpoint: String,
            headers: Headers,
            envoyContainer: EnvoyContainer = envoyContainer1
        ): Response = IngressOperations(envoyContainer).callLocalService(endpoint, headers)

        fun callPostLocalService(
            endpoint: String,
            headers: Headers,
            body: RequestBody,
            envoyContainer: EnvoyContainer = envoyContainer1
        ): Response = IngressOperations(envoyContainer).callPostLocalService(endpoint, headers, body)

        fun ToxiproxyContainer.createProxyToEnvoyIngress(envoy: EnvoyContainer) = this.createProxy(
            targetIp = envoy.ipAddress(), targetPort = EnvoyContainer.INGRESS_LISTENER_CONTAINER_PORT
        )

        private fun waitForConsulSync() {
            await().atMost(defaultDuration).until { !callEcho().use { it.isSuccessful } }
        }

        private fun defaultAppFactory(): (Int) -> EnvoyControlRunnerTestApp {
            return { consulPort ->
                EnvoyControlRunnerTestApp(
                    consulPort = consulPort
                )
            }
        }

        fun <T> untilAsserted(wait: org.awaitility.Duration = defaultDuration, fn: () -> (T)): T {
            var lastResult: T? = null
            await().atMost(wait).untilAsserted({ lastResult = fn() })
            assertThat(lastResult).isNotNull
            return lastResult!!
        }
    }

    protected fun callServiceRepeatedly(
        service: String,
        stats: CallStats,
        minRepeat: Int = 1,
        maxRepeat: Int = 100,
        repeatUntil: (ResponseWithBody) -> Boolean = { false },
        headers: Map<String, String> = mapOf(),
        pathAndQuery: String = "",
        assertNoErrors: Boolean = true,
        fromEnvoy: EnvoyContainer = envoyContainer1
    ): CallStats = EgressOperations(fromEnvoy).callServiceRepeatedly(
            service, stats, minRepeat, maxRepeat, repeatUntil, headers, pathAndQuery, assertNoErrors
    )

    private fun waitForEchoServices(instances: Int) {
        untilAsserted {
            assertThat(envoyContainer1.admin().numOfEndpoints(clusterName = "echo")).isEqualTo(instances)
        }
    }

    fun checkTrafficRoutedToSecondInstance(id: String) {
        // given
        // we first register a new instance and then remove other to maintain cluster presence in Envoy
        registerService(name = "echo", container = echoContainer2)
        waitForEchoServices(instances = 2)

        deregisterService(id)
        waitForEchoServices(instances = 1)

        untilAsserted {
            // when
            val response = callEcho()

            // then
            assertThat(response).isOk().isFrom(echoContainer2)
        }
    }

    fun waitUntilEchoCalledThroughEnvoyResponds(target: EchoContainer) {
        untilAsserted {
            // when
            val response = callEcho()

            // then
            assertThat(response).isOk().isFrom(target)
        }
    }

    /**
     * We have to retrieve the bean manually instead of @Autowired because the app is created in manual way
     * instead of using the JUnit Spring Extension
     */
    inline fun <reified T> bean(): T = envoyControl1.bean(T::class.java)

    fun waitForReadyServices(vararg serviceNames: String) {
        serviceNames.forEach {
            untilAsserted {
                callService(it).also {
                    assertThat(it).isOk()
                }
            }
        }
    }

    fun ObjectAssert<Response>.isOk(): ObjectAssert<Response> {
        matches { it.isSuccessful }
        return this
    }

    fun ObjectAssert<Response>.isFrom(echoContainer: EchoContainer): ObjectAssert<Response> {
        matches {
            it.body()?.use { it.string().contains(echoContainer.response) } ?: false
        }
        return this
    }

    fun ObjectAssert<Response>.hasHostHeaderWithValue(overriddenHostHeader: String): ObjectAssert<Response> {
        matches({
            it.body()?.use { it.string().contains("\"host\": \"$overriddenHostHeader\"") } ?: false
        }, "Header Host should be overridden with value: $overriddenHostHeader")
        return this
    }

    fun ObjectAssert<Response>.isUnreachable(): ObjectAssert<Response> {
        matches({
            it.body()?.close()
            it.code() == 503 || it.code() == 504
        }, "is unreachable")
        return this
    }

    fun ObjectAssert<Response>.isForbidden(): ObjectAssert<Response> {
        matches({
            it.body()?.close()
            it.code() == 403
        }, "is forbidden")
        return this
    }

    fun ObjectAssert<Response>.hasLocationHeaderFrom(serviceName: String): ObjectAssert<Response> {
        matches { it.headers("location").contains("http://$serviceName/") }
        return this
    }

    fun ObjectAssert<Response>.hasXEnvoyUpstreamRemoteAddressFrom(echoContainer: EchoContainer): ObjectAssert<Response> {
        matches { it.headers("x-envoy-upstream-remote-address").contains(echoContainer.address()) }
        return this
    }

    fun ObjectAssert<Health>.isStatusHealthy(): ObjectAssert<Health> {
        matches { it.status == Status.UP }
        return this
    }

    fun ObjectAssert<Health>.hasEnvoyControlCheckPassed(): ObjectAssert<Health> {
        matches { it.details.get("envoyControl")?.status == Status.UP }
        return this
    }

    fun ObjectAssert<ServicesState>.hasServiceStateChanged(): ObjectAssert<ServicesState> {
        matches { it.serviceNames().isNotEmpty() }
        return this
    }

    @AfterEach
    open fun cleanupTest() {
        deregisterAllServices()
        envoyContainer1.admin().resetCounters()
        if (envoys == 2) {
            envoyContainer2.admin().resetCounters()
        }
        waitForConsulSync()
    }
}
