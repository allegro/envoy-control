package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isFrom
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isOk
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.Ads
import pl.allegro.tech.servicemesh.envoycontrol.config.AdsV3
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension

class EnvoyControlV2toV3MigrationSmokeTest {

    companion object {

        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(consul)

        @JvmField
        @RegisterExtension
        val serviceEnvoyV2 = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val serviceEnvoyV3 = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val envoyV2 = EnvoyExtension(envoyControl, serviceEnvoyV2, Ads)

        @JvmField
        @RegisterExtension
        val envoyV3 = EnvoyExtension(envoyControl, serviceEnvoyV3, AdsV3)
    }

    @Test
    fun `should create a server listening on a port`() {
        untilAsserted {
            // when
            val ingressRootEnvoyV2 = envoyV2.ingressOperations.callLocalService("")
            val ingressRootEnvoyV3 = envoyV3.ingressOperations.callLocalService("")

            // then
            assertThat(ingressRootEnvoyV2).isFrom(serviceEnvoyV2).isOk()
            assertThat(ingressRootEnvoyV3).isFrom(serviceEnvoyV3).isOk()
        }
    }
}
