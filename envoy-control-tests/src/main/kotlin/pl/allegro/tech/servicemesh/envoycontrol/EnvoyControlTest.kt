package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.config.Ads
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration
import pl.allegro.tech.servicemesh.envoycontrol.config.Xds

internal class AdsEnvoyControlTest : EnvoyControlTest() {
    companion object {

        @JvmStatic
        @BeforeAll
        fun adsSetup() {
            setup(envoyConfig = Ads)
        }
    }
}

internal class XdsEnvoyControlTest : EnvoyControlTest() {
    companion object {

        @JvmStatic
        @BeforeAll
        fun nonAdsSetup() {
            setup(envoyConfig = Xds)
        }
    }
}

internal abstract class EnvoyControlTest : EnvoyControlTestConfiguration() {

    @Test
    fun `should allow proxy-ing request using envoy`() {
        // given
        registerService(name = "echo")

        untilAsserted {
            // when
            val response = callEcho()

            // then
            assertThat(response).isOk().isFrom(echoContainer)
        }
    }

    @Test
    fun `should route traffic to the second instance when first is deregistered`() {
        // given
        val id = registerService(name = "echo")

        untilAsserted {
            // when
            val response = callEcho()

            // then
            assertThat(response).isOk().isFrom(echoContainer)
        }

        // given
        // we first register a new instance and then remove other to maintain cluster presence in Envoy
        registerService(name = "echo", container = echoContainer2)
        waitForEchoServices(instances = 2)

        deregisterService(id)
        waitForEchoServices(instances = 1)

        untilAsserted {
            // when
            val response = callEcho()

            // then
            assertThat(response).isOk().isFrom(echoContainer2)
        }
    }

    private fun waitForEchoServices(instances: Int) {
        untilAsserted {
            assertThat(envoyContainer1.admin().numOfEndpoints(clusterName = "echo")).isEqualTo(instances)
        }
    }

    @Test
    fun `should assign endpoints to correct zones`() {
        // given
        registerService(name = "echo")

        untilAsserted {
            // when
            val adminInstance = envoyContainer1.admin().zone(cluster = "echo", ip = echoContainer.ipAddress())

            // then
            assertThat(adminInstance).isNotNull
            assertThat(adminInstance!!.zone).isEqualTo("dc1")
        }
    }
}
