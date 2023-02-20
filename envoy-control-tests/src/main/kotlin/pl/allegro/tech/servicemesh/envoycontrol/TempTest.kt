package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isFrom
import pl.allegro.tech.servicemesh.envoycontrol.config.Ads
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.ServiceExtension

class TempTest {

    companion object {
        private val properties = mapOf(
            "envoy-control.envoy.snapshot.routing.service-tags.enabled" to true,
            "envoy-control.envoy.snapshot.routing.service-tags.metadata-key" to "tag",
        )

        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(consul, properties)

        @JvmField
        @RegisterExtension
        val service = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val envoy = EnvoyExtension(envoyControl, service, config = Ads)
    }


    // TODO: test x-envoy-upstream-remote-address in response

    @Test
    fun debug() {
        consul.server.operations.registerService(name = "echo", tags = listOf("lorem", "ipsum"), extension = service)
        consul.server.operations.registerService(name = "service-1", tags = listOf(), extension = service)
        envoy.container.addDnsEntry("myhttp.example.com", service.container().ipAddress())

        envoy.waitForReadyServices("echo", "service-1", "myhttp.example.com:5678")

        val adminAddress = envoy.container.adminUrl()

        run {
            // when
            val response = envoy.egressOperations.callService(service = "echo")

            // then
            val serviceTagsResponseHeader = response.headers["x-envoy-upstream-service-tags"]

            assertThat(response).isFrom(service)
            assertThat(serviceTagsResponseHeader).isEqualTo("""["lorem","ipsum"]""")
        }

        run {
            // when
            val response = envoy.egressOperations.callService(service = "service-1")

            // then
            val serviceTagsResponseHeader = response.headers["x-envoy-upstream-service-tags"]

            assertThat(response).isFrom(service)
            assertThat(serviceTagsResponseHeader).isNull()
        }

        run {
            // when
            val response = envoy.egressOperations.callDomain("myhttp.example.com:5678")

            // then
            val serviceTagsResponseHeader = response.headers["x-envoy-upstream-service-tags"]

            assertThat(response).isFrom(service)
            assertThat(serviceTagsResponseHeader).isNull()
        }


    }
}
