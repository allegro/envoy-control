package pl.allegro.tech.servicemesh.envoycontrol

import okhttp3.Response
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.ObjectAssert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.AdsAllDependencies
import pl.allegro.tech.servicemesh.envoycontrol.config.Xds
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoContainer
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.GenericServiceExtension

class CompressionFilterTest {

    companion object {
        private const val SERVICE_NAME = "service-1"
        private const val DOWNSTREAM_SERVICE_NAME = "echo2"
        private const val LONG_STRING = "Workshallmeantheworkofauthorship,whetherinSourceorObjectform," +
            "madeavailableundertheLicensesindicatedbyacopyrightnoticethatisincludedinorattachedto" +
            "thework(anexampleisprovidedintheAppendixbelow)."
        private val serviceConfig = """
            node:
              metadata:
                proxy_settings:
                  incoming:
                    unlistedEndpointsPolicy: log
                    endpoints: []
        """.trimIndent()
        private val config = Xds.copy(configOverride = serviceConfig, serviceName = SERVICE_NAME)
        private val unListedServiceConfig = AdsAllDependencies
        private val longText = LONG_STRING.repeat(100)

        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(
            consul,
            mapOf(
                "envoy-control.envoy.snapshot.compression.gzip.enabled" to true,
                "envoy-control.envoy.snapshot.compression.brotli.enabled" to true,
                "envoy-control.envoy.snapshot.compression.minContentLength" to 100,
                "envoy-control.envoy.snapshot.compression.responseCompressionEnabled" to true,
                "envoy-control.envoy.snapshot.compression.enableForServices" to listOf(DOWNSTREAM_SERVICE_NAME),
                "envoy-control.envoy.snapshot.outgoing-permissions.servicesAllowedToUseWildcard" to "test-service"

            )
        )

        @JvmField
        @RegisterExtension
        val service =
            GenericServiceExtension(EchoContainer(longText))

        @JvmField
        @RegisterExtension
        val noCompressionService = GenericServiceExtension(EchoContainer(longText))

        @JvmField
        @RegisterExtension
        val downstreamService = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val downstreamEnvoy = EnvoyExtension(envoyControl, downstreamService, Xds)

        @JvmField
        @RegisterExtension
        val noCompressionEnvoy = EnvoyExtension(envoyControl, noCompressionService, unListedServiceConfig)

        @JvmField
        @RegisterExtension
        val serviceEnvoy = EnvoyExtension(envoyControl, config = config, localService = service)
    }

    @Test
    fun `should compress response with brotli`() {
        consul.server.operations.registerServiceWithEnvoyOnIngress(serviceEnvoy, name = SERVICE_NAME)
        downstreamEnvoy.waitForReadyServices(SERVICE_NAME)
        untilAsserted {
            val response =
                downstreamEnvoy.egressOperations.callService(SERVICE_NAME, headers = mapOf("accept-encoding" to "br"))
            assertThat(response).isCompressedWith("br")
        }
    }

    @Test
    fun `should compress response with gzip`() {
        consul.server.operations.registerServiceWithEnvoyOnIngress(serviceEnvoy, name = SERVICE_NAME)
        downstreamEnvoy.waitForReadyServices(SERVICE_NAME)
        untilAsserted {
            val response =
                downstreamEnvoy.egressOperations.callService(SERVICE_NAME, headers = mapOf("accept-encoding" to "gzip"))
            assertThat(response).isCompressedWith("gzip")
        }
    }

    @Test
    fun `should not compress response when accept encoding header is missing`() {
        consul.server.operations.registerServiceWithEnvoyOnIngress(serviceEnvoy, name = SERVICE_NAME)
        downstreamEnvoy.waitForReadyServices(SERVICE_NAME)
        untilAsserted {
            val response =
                downstreamEnvoy.egressOperations.callService(SERVICE_NAME)
            assertThat(response).isNotCompressed()
        }
    }

    @Test
    fun `should not enable compression on unlisted service`() {
        consul.server.operations.registerServiceWithEnvoyOnIngress(serviceEnvoy, name = SERVICE_NAME)
        noCompressionEnvoy.waitForReadyServices(SERVICE_NAME)
        untilAsserted {
            val response =
                noCompressionEnvoy.egressOperations.callService(SERVICE_NAME, headers = mapOf("accept-encoding" to "gzip"))
            println(response.headers.toString())
            assertThat(response).isNotCompressed()
        }
    }

    private fun ObjectAssert<Response>.isCompressedWith(encoding: String): ObjectAssert<Response> {
        matches { it.isSuccessful && it.headers.any { x -> x.first == "content-encoding" && x.second == encoding } }
        return this
    }

    private fun ObjectAssert<Response>.isNotCompressed(): ObjectAssert<Response> {
        matches { it.isSuccessful && it.headers.none { x -> x.first == "content-encoding" } }
        return this
    }
}
