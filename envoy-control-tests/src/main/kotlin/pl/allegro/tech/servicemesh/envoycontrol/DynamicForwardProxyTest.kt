package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
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
import pl.allegro.tech.servicemesh.envoycontrol.config.service.GenericServiceExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.HttpsEchoContainer
import java.time.Duration

class DynamicForwardProxyTest {

    companion object {

        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(
            consul, mapOf(
                "envoy-control.envoy.snapshot.trustedCaFile" to "/app/root-ca.crt",
                "envoy-control.envoy.snapshot.outgoing-permissions.enabled" to true
            )
        )

        @JvmField
        @RegisterExtension
        val service = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val envoy = EnvoyExtension(envoyControl, config = AdsDynamicForwardProxy)

        @JvmField
        @RegisterExtension
        val httpsService = GenericServiceExtension(HttpsEchoContainer())

        @JvmStatic
        @BeforeAll
        fun setup() {
            envoy.container.addDnsEntry("my.example.com", httpsService.container().ipAddress())
        }
    }

    @Test
    fun `should allow to request domains with suffix com`() {
        // given
        untilAsserted(wait = Duration.ofSeconds(1200)) {
            // when
            val reachableDomainResponse = envoy.egressOperations.callDomain("my.example.com")

            // then
            assertThat(reachableDomainResponse).isOk()
        }
    }

    @Test
    fun `should not request domains with suffix org`() {
        // given
        untilAsserted {
            // when
            val reachableDomainResponse = envoy.egressOperations.callDomain("my.example.org")

            // then
            assertThat(reachableDomainResponse).isUnreachable()
        }
    }
}
