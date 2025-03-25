package pl.allegro.tech.servicemesh.envoycontrol.routing

import okhttp3.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isOk
import pl.allegro.tech.servicemesh.envoycontrol.config.RandomConfigFile
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.GenericServiceExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.HttpsEchoContainer
import pl.allegro.tech.servicemesh.envoycontrol.config.service.ServiceExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.asHttpsEchoResponse

class RoutingHeadersTest : TestBase(echoService, envoy) {
    companion object {
        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(consul, properties(addUpstreamServiceTagsHeader = true))

        @JvmField
        @RegisterExtension
        val echoService = GenericServiceExtension(HttpsEchoContainer())

        @JvmField
        @RegisterExtension
        val envoy = EnvoyExtension(envoyControl, config = RandomConfigFile.copy(configOverride = proxySettings))
    }

    private fun registerServices() {
        listOf("echo", "echo-disabled", "echo-one-tag", "echo-no-tag").forEach { service ->
            consul.server.operations.registerService(name = service, extension = echoService, tags = allTags)
        }
        listOf("echo", "echo-disabled", "echo-one-tag", "echo-no-tag").forEach { service ->
            waitForEndpointReady(service, echoService, envoy)
        }
    }

    @Test
    fun `should add correct x-service-tag-preference header to upstream request`() {
        // given
        registerServices()

        // when
        val echoResponse = envoy.egressOperations.callService("echo").asHttpsEchoResponse()
        val echoDisabledResponse = envoy.egressOperations.callService("echo-disabled").asHttpsEchoResponse()
        val echoOneTagResponse = envoy.egressOperations.callService("echo-one-tag").asHttpsEchoResponse()
        val echoNoTagResponse = envoy.egressOperations.callService("echo-no-tag").asHttpsEchoResponse()

        // then
        assertThat(echoResponse).isOk()
        assertThat(echoResponse.requestHeaders).containsEntry("x-service-tag-preference", "ipsum|lorem")

        assertThat(echoDisabledResponse).isOk()
        assertThat(echoDisabledResponse.requestHeaders).doesNotContainKey("x-service-tag-preference")

        assertThat(echoOneTagResponse).isOk()
        assertThat(echoOneTagResponse.requestHeaders).containsEntry("x-service-tag-preference", "one")

        assertThat(echoNoTagResponse).isOk()
        assertThat(echoNoTagResponse.requestHeaders).doesNotContainKey("x-service-tag-preference")
    }

    @Test
    fun `should not override service-tag preference header already set in the request`() {
        // given
        registerServices()

        // when
        val echoResponse = envoy.egressOperations.callService(
            service = "echo",
            headers = mapOf("x-service-tag-preference" to "custom|ipsum|lorem")
        ).asHttpsEchoResponse()

        // then
        assertThat(echoResponse).isOk()
        assertThat(echoResponse.requestHeaders).containsEntry("x-service-tag-preference", "custom|ipsum|lorem")
    }

    @Test
    fun `should add upstream service tags to a response`() =
        upstreamServiceTagsInResponseTest(service = "echo") { echoResponse ->
            // then
            assertThat(echoResponse.headers("x-envoy-upstream-service-tags")).isEqualTo(listOf("""["ipsum","lorem","one"]"""))
        }

    @Test
    fun `should add upstream service tags to a response even if autoServiceTag is disabled`() =
        upstreamServiceTagsInResponseTest(service = "echo-disabled") { echoResponse ->
            // then
            assertThat(echoResponse.headers("x-envoy-upstream-service-tags")).isEqualTo(listOf("""["ipsum","lorem","one"]"""))
        }
}

class UpstreamTagHeadersDisabledTest : TestBase(echoService, envoy) {
    companion object {
        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(consul, properties(addUpstreamServiceTagsHeader = false))

        @JvmField
        @RegisterExtension
        val echoService = GenericServiceExtension(HttpsEchoContainer())

        @JvmField
        @RegisterExtension
        val envoy = EnvoyExtension(envoyControl, config = RandomConfigFile.copy(configOverride = proxySettings))
    }

    @Test
    fun `should not add upstream service tags to a response`() =
        upstreamServiceTagsInResponseTest(service = "echo") { echoResponse ->
            assertThat(echoResponse.headers("x-envoy-upstream-service-tags")).isEmpty()
        }
}

abstract class TestBase(private val echoService: ServiceExtension<*>, private val envoy: EnvoyExtension) {
    companion object {
        fun properties(addUpstreamServiceTagsHeader: Boolean = false) = mapOf(
            "envoy-control.envoy.snapshot.routing.service-tags.enabled" to true,
            "envoy-control.envoy.snapshot.routing.service-tags.auto-service-tag-enabled" to true,
            "envoy-control.envoy.snapshot.routing.service-tags.add-upstream-service-tags-header" to addUpstreamServiceTagsHeader
        )

        // language=yaml
        val proxySettings = """
            node:
              metadata:
                proxy_settings:
                  outgoing:
                    routingPolicy:
                      autoServiceTag: true
                      serviceTagPreference: ["ipsum", "lorem"]
                      fallbackToAnyInstance: true
                    dependencies:
                      - service: "echo" 
                      - service: "echo-disabled"
                        routingPolicy:
                          autoServiceTag: false
                      - service: "echo-one-tag"  
                        routingPolicy:
                          serviceTagPreference: ["one"]
                      - service: "echo-no-tag"  
                        routingPolicy:
                          serviceTagPreference: []
        """.trimIndent()

        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        val allTags = listOf("ipsum", "lorem", "one")
    }

    protected fun upstreamServiceTagsInResponseTest(service: String, assertions: (echoResponse: Response) -> Unit) {
        // given
        consul.server.operations.registerService(name = service, extension = echoService, tags = allTags)
        waitForEndpointReady(service, echoService, envoy)

        // when
        val echoResponse = envoy.egressOperations.callService(service)

        // then
        assertions(echoResponse)
    }

    protected fun waitForEndpointReady(
        serviceName: String,
        serviceInstance: ServiceExtension<*>,
        envoy: EnvoyExtension
    ) {
        envoy.waitForClusterEndpointHealthy(cluster = serviceName, endpointIp = serviceInstance.container().ipAddress())
    }
}
