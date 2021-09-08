@file:Suppress("MagicNumber")

package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.Ads
import pl.allegro.tech.servicemesh.envoycontrol.config.AdsAllDependencies
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyContainer.Companion.EGRESS_LISTENER_CONTAINER_PORT
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.GenericServiceExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.HttpsEchoContainer
import pl.allegro.tech.servicemesh.envoycontrol.config.service.HttpsEchoResponse

class AllDependenciesEnvoyOriginalDstListenerTests : EnvoyOriginalDstListenerTests {
    companion object {

        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(
            consul, properties = mapOf(
                "envoy-control.envoy.snapshot.outgoing-permissions.servicesAllowedToUseWildcard" to "test-service"
            )
        )

        @JvmField
        @RegisterExtension
        val service = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val httpDomain = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val httpsService = GenericServiceExtension(HttpsEchoContainer())

        @JvmField
        @RegisterExtension
        val envoy = EnvoyExtension(
            envoyControl,
            service,
            AdsAllDependencies.copy(
                configOverride = """
                  node:
                    metadata:
                      use_transparent_proxy: true
            """.trimIndent()
            )
        )

        @JvmStatic
        @BeforeAll
        fun setup() {
            envoy.container.addDnsEntry("my.example.com", httpsService.container().ipAddress())
            envoy.container.addDnsEntry("myhttp.example.com", httpDomain.container().ipAddress())
        }

        @JvmStatic
        @AfterAll
        fun cleanup() {
            envoy.container.removeDnsEntry("my.example.com")
            envoy.container.removeDnsEntry("myhttp.example.com")
        }
    }

    override fun consul() = consul

    override fun envoyControl() = envoyControl

    override fun service() = service

    override fun envoy() = envoy

    override fun httpDomain() = httpDomain

    override fun httpsService() = httpsService
}

class ListedDependenciesEnvoyOriginalDstListenerTests : EnvoyOriginalDstListenerTests {
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
        val httpDomain = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val httpsService = GenericServiceExtension(HttpsEchoContainer())

        @JvmField
        @RegisterExtension
        val envoy = EnvoyExtension(
            envoyControl,
            service,
            Ads.copy(
                configOverride = """
                  node:
                    metadata:
                      use_transparent_proxy: true
            """.trimIndent()
            )
        )

        @JvmStatic
        @BeforeAll
        fun setup() {
            envoy.container.addDnsEntry("my.example.com", httpsService.container().ipAddress())
            envoy.container.addDnsEntry("myhttp.example.com", httpDomain.container().ipAddress())
        }

        @JvmStatic
        @AfterAll
        fun cleanup() {
            envoy.container.removeDnsEntry("my.example.com")
            envoy.container.removeDnsEntry("myhttp.example.com")
        }
    }

    override fun consul() = consul

    override fun envoyControl() = envoyControl

    override fun service() = service

    override fun envoy() = envoy

    override fun httpDomain() = httpDomain

    override fun httpsService() = httpsService
}

interface EnvoyOriginalDstListenerTests {

    fun consul(): ConsulExtension

    fun envoyControl(): EnvoyControlExtension

    fun service(): EchoServiceExtension

    fun envoy(): EnvoyExtension

    fun httpDomain(): EchoServiceExtension

    fun httpsService(): GenericServiceExtension<HttpsEchoContainer>

    @Test
    fun `should use transparent proxy to communicate with services and domain`() {
        envoy().container.addIptablesRedirect(EGRESS_LISTENER_CONTAINER_PORT, 80)
        envoy().container.addIptablesRedirect(EGRESS_LISTENER_CONTAINER_PORT, 5678)
        envoy().container.addHost("echo", "127.0.0.1")
        consul().server.operations.registerService(service(), name = "echo")

        untilAsserted {
            // when
            val resultService = envoy().container.callInContainer("echo")

            // then
            assertThat(resultService).isNotNull
            assertThat(resultService!!.stdout.trim()).isEqualTo(service().container().response)
            assertThat(envoy().container.admin().statValue("cluster.echo.upstream_rq_200")?.toInt()).isEqualTo(1)
        }

        untilAsserted {
            // when
            val resultDomain = envoy().container.callInContainer("myhttp.example.com:5678")

            // then
            assertThat(resultDomain).isNotNull
            assertThat(resultDomain!!.stdout.trim()).isEqualTo(httpDomain().container().response)
            assertThat(
                envoy().container.admin().statValue("cluster.myhttp_example_com_5678.upstream_rq_200")?.toInt()
            ).isEqualTo(1)
        }
    }

    @Test
    fun `should use transparent proxy to communicate with https domains`() {
        envoy().container.addIptablesRedirect(EGRESS_LISTENER_CONTAINER_PORT, 443)

        untilAsserted {
            // when
            val response = envoy().container.callInContainer("my.example.com", isHttps = true)

            // then
            assertThat(response).isNotNull
            assertThat(response!!.stdout).isNotBlank()
            assertThat(getSNI(response!!.stdout)).isEqualTo("my.example.com")
            assertThat(getHostname(response!!.stdout)).isEqualTo(httpsService().container().containerName())
        }
    }

    @AfterEach
    fun cleanIpTables() {
        envoy().container.cleanIptables()
        envoy().container.removeHost("echo")
    }

    private fun getSNI(body: String): String {
        return HttpsEchoResponse.objectMapper.readTree(body).at("/connection/servername").textValue()
    }

    private fun getHostname(body: String): String {
        return HttpsEchoResponse.objectMapper.readTree(body).at("/os/hostname").textValue()
    }
}
