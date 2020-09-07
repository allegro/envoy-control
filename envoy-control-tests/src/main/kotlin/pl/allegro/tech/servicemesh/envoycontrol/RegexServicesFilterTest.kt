package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isFrom
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isOk
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isUnreachable
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension

class RegexServicesFilterTest {

    companion object {

        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(consul, mapOf(
            "envoy-control.service-filters.excluded-names-patterns" to ".*-[1-2]$".toRegex()
        ))

        @JvmField
        @RegisterExtension
        val service = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val envoy = EnvoyExtension(envoyControl)
    }

    @Test
    fun `should not reach service whose name ends with number from 1 to 4`() {
        // given
        consul.server.operations.registerService(service, name = "service-1")
        consul.server.operations.registerService(service, name = "service-2")
        consul.server.operations.registerService(service, name = "service-3")

        untilAsserted {
            // when
            val response1 = envoy.egressOperations.callService("service-1")
            val response2 = envoy.egressOperations.callService("service-2")
            val response3 = envoy.egressOperations.callService("service-3")

            // then
            assertThat(response1).isUnreachable()
            assertThat(response2).isUnreachable()
            assertThat(response3).isOk().isFrom(service)
        }
    }
}
