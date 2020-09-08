package pl.allegro.tech.servicemesh.envoycontrol.permissions

import okhttp3.Headers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.testcontainers.junit.jupiter.Container
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isRbacAccessLog
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo1EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.containers.ToxiproxyContainer
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyContainer
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlRunnerTestApp

@SuppressWarnings("LargeClass")
internal class IncomingPermissionsEndpointsTest : EnvoyControlTestConfiguration() {

    companion object {

        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(consul, mapOf(
            "envoy-control.envoy.snapshot.incoming-permissions.enabled" to true,
            "envoy-control.envoy.snapshot.routes.status.create-virtual-cluster" to true
        ))

        // language=yaml
        private val echoOneYaml = """
            node: 
              metadata: 
                proxy_settings: 
                  incoming:
                    unlistedEndpointsPolicy: blockAndLog
                    endpoints:
                    - path: "/path"
                      clients: ["echo2"]
                    - pathPrefix: "/prefix"
                      clients: ["echo2"]
                    - pathRegex: "/regex/.*/segment"
                      clients: ["echo2"]
                    roles: []
                  outgoing:
                    dependencies:
                      - service: "echo2"
        """.trimIndent()

        // language=yaml
        private val echoTwoYaml = """
            node:
              metadata:
                proxy_settings:
                  outgoing:
                    dependencies:
                      - service: "echo1"
        """.trimIndent()

        private val echoOneConfig = Echo1EnvoyAuthConfig.copy(configOverride = echoOneYaml)
        private val echoTwoConfig = Echo1EnvoyAuthConfig.copy(configOverride = echoTwoYaml)

        @JvmField
        @RegisterExtension
        val envoyOne = EnvoyExtension(envoyControl, config = echoOneConfig)

        @JvmField
        @RegisterExtension
        val envoyTwo = EnvoyExtension(envoyControl, config = echoTwoConfig)

        private val echo1Envoy by lazy { envoyContainer1 }
        private val echo2Envoy by lazy { envoyContainer2 }

        @Container
        private val sourceIpClient = ToxiproxyContainer(exposedPortsCount = 2).withNetwork(network)
        private val sourceIpClientToEchoProxy by lazy { sourceIpClient.createProxyToEnvoyIngress(envoy = echo1Envoy) }
        private val sourceIpClientToEcho2Proxy by lazy { sourceIpClient.createProxyToEnvoyIngress(envoy = echo2Envoy) }

        private val echoLocalService by lazy { localServiceContainer }
        private val echo2LocalService by lazy { echoContainer2 }

        @JvmStatic
        @BeforeAll
        fun setupTest() {
            setup(appFactoryForEc1 = { consulPort ->
                EnvoyControlRunnerTestApp(properties = properties(sourceIpClient.ipAddress()), consulPort = consulPort)
            },
                envoys = 2,
                envoyConfig = echoOneConfig,
                secondEnvoyConfig = echoTwoConfig
            )
            echo3Envoy = createEnvoyContainerWithEcho3Certificate(configOverride = IncomingPermissionsLoggingModeTest.echo3Config)
            echo3Envoy.start()

            registerServiceWithEnvoyOnIngress(name = "echo", envoy = echoEnvoy, tags = listOf("mtls:enabled"))
            registerServiceWithEnvoyOnIngress(name = "echo2", envoy = echo2Envoy, tags = listOf("mtls:enabled"))

            waitForEnvoysInitialized()
        }

        private fun waitForEnvoysInitialized() {
            untilAsserted {
                assertThat(echo3Envoy.admin().isEndpointHealthy("echo", echoEnvoy.ipAddress())).isTrue()
                assertThat(echo3Envoy.admin().isEndpointHealthy("echo2", echo2Envoy.ipAddress())).isTrue()
                assertThat(echoEnvoy.admin().isEndpointHealthy("echo2", echo2Envoy.ipAddress())).isTrue()
                assertThat(echo2Envoy.admin().isEndpointHealthy("echo", echoEnvoy.ipAddress())).isTrue()
            }
        }
    }

    @Test
    fun `echo should allow echo3 to access 'example-endpoint-action1' on exact path with param`() {
        // when
        val echoResponeOne = envoyOne.ingressOperations.callLocalService(endpoint = "/path", headers = Headers.of())
//        val echoResponseOne = call(service = "echo", from = envoyTwo, path = "/example-endpoint/1/action1")
//        val echoResponseTwo = call(service = "echo", from = envoyTwo, path = "/example-endpoint/2/action1")
//        val echoResponseThree = call(service = "echo", from = envoyTwo, path = "/example-endpoint/3/action1")
//
//        // then
        assertThat(echoResponeOne).isOk()
//        assertThat(echoResponseTwo).isOk().isFrom(echoLocalService)
//        assertThat(echoResponseThree).isOk().isFrom(echoLocalService)
//        assertThat(echoEnvoy.ingressSslRequests).isEqualTo(3)
//        assertThat(echoEnvoy).hasNoRBACDenials()
    }

    @Test
    fun `echo should NOT allow echo3 to access 'wildcard-in-the-middle' on path with param and with wildcard`() {
//        // when
//        val echoResponseAllowed = call(service = "echo", from = echo3Envoy, path = "/wildcard-in-the-middle/1/rest")
//        val echoResponseNotAllowed = call(service = "echo", from = echo3Envoy, path = "/wildcard-in-the-middles/1/rest")
//
//        // then
//        assertThat(echoResponseAllowed).isOk().isFrom(echoLocalService)
//        assertThat(echoResponseNotAllowed).isForbidden()
//        assertThat(echoEnvoy.ingressSslRequests).isEqualTo(2)
//        assertThat(echoEnvoy).hasOneAccessDenialWithActionBlock(
//            protocol = "https",
//            path = "/wildcard-in-the-middles/1/rest",
//            method = "GET",
//            clientName = "echo3",
//            clientIp = echo3Envoy.ipAddress()
//        )
    }

//    @BeforeEach
//    fun startRecordingRBACLogs() {
//        echoEnvoy.recordRBACLogs()
//        echo2Envoy.recordRBACLogs()
//    }
//
//    fun stopRecordingRBACLogs() {
//        echoEnvoy.logRecorder.stopRecording()
//        echo2Envoy.logRecorder.stopRecording()
//    }
//
//    @AfterEach
//    override fun cleanupTest() {
//        listOf(echoEnvoy, echo2Envoy, echo3Envoy).forEach { it.admin().resetCounters() }
//        stopRecordingRBACLogs()
//    }

    private val EnvoyContainer.ingressSslRequests: Int?
        get() = this.admin().statValue("http.ingress_https.downstream_rq_completed")?.toInt()

    private fun EnvoyContainer.recordRBACLogs() {
        logRecorder.recordLogs(::isRbacAccessLog)
    }
}
