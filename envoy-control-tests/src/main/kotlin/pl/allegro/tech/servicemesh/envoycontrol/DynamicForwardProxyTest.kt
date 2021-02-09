package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isOk
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isUnreachable
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.AdsDynamicForwardProxy
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension

class DynamicForwardProxyTest {

    companion object {

        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(
            consul, mapOf(
                "envoy-control.envoy.snapshot.outgoing-permissions.enabled" to true
            )
        )

        @JvmField
        @RegisterExtension
        val service = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val envoy = EnvoyExtension(envoyControl, config = AdsDynamicForwardProxy)
    }

    @Test
    fun `should allow to request domains with suffix com`() {
        // given
        untilAsserted {
            // when
            val reachableDomainResponse = envoy.egressOperations.callDomain("www.example.com")
            val reachableDomainResponse2 = envoy.egressOperations.callDomain("www.wp.pl")

            // then
            assertThat(reachableDomainResponse).isOk()
            assertThat(reachableDomainResponse2).isOk()
        }
    }

    @Test
    fun `should not request domains with suffix org`() {
        // given
        untilAsserted {
            // when
            val reachableDomainResponse = envoy.egressOperations.callDomain("www.example.org")

            // then
            assertThat(reachableDomainResponse).isUnreachable()
        }
    }
}
