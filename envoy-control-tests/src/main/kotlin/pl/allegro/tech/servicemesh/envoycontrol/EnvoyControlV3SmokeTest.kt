package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isFrom
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isOk
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.Ads
import pl.allegro.tech.servicemesh.envoycontrol.config.AdsV2
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension

class EnvoyControlV3SmokeTest {

    companion object {

        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(consul, mapOf(
            "envoy-control.envoy.snapshot.support-v2-configuration" to false
        ))

        @JvmField
        @RegisterExtension
        val serviceEnvoyV3 = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val envoyV3 = EnvoyExtension(envoyControl, serviceEnvoyV3, Ads)
    }

    @Test
    fun `should create a server listening on a port`() {
        untilAsserted {
            // when
            val ingressRootEnvoyV3 = envoyV3.ingressOperations.callLocalService("")

            // then
            assertThat(ingressRootEnvoyV3).isFrom(serviceEnvoyV3).isOk()
        }
    }
}
