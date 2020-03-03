package pl.allegro.tech.servicemesh.envoycontrol.ssl

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.config.Ads
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlRunnerTestApp
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration
import pl.allegro.tech.servicemesh.envoycontrol.config.Xds

class AdsEnvoySANValidationTest : EnvoySANValidationTest() {

    companion object {
        private val properties = mapOf(
                "envoy-control.envoy.snapshot.trustedCaFile" to "/usr/local/share/ca-certificates/root-ca.crt"
        )

        @JvmStatic
        @BeforeAll
        fun setupTest() {
            setup(
                envoyConfig = Ads,
                appFactoryForEc1 = { consulPort -> EnvoyControlRunnerTestApp(properties, consulPort) }
            )
        }
    }
}

class XdsEnvoySANValidationTest : EnvoySANValidationTest() {
    companion object {
        private val properties = mapOf(
                "envoy-control.envoy.snapshot.trustedCaFile" to "/usr/local/share/ca-certificates/root-ca.crt"
        )

        @JvmStatic
        @BeforeAll
        fun setupTest() {
            setup(
                envoyConfig = Xds,
                appFactoryForEc1 = { consulPort -> EnvoyControlRunnerTestApp(properties, consulPort) }
            )
        }
    }
}

abstract class EnvoySANValidationTest : EnvoyControlTestConfiguration() {

    @Test
    fun `should validate SAN part of certificate`() {
        // given
        envoyContainer1.addHost("my.example.com", httpsEchoContainer.ipAddress())
        envoyContainer1.addHost("bad.host.example.com", httpsEchoContainer.ipAddress())

        untilAsserted {
            // when
            val reachableResponse = callDomain("my.example.com")

            assertThat(reachableResponse).isOk()
        }
    }

    @Test
    fun `should reject certificate without matching SAN`() {
        // given
        envoyContainer1.addHost("bad.host.example.com", httpsEchoContainer.ipAddress())

        untilAsserted {
            // when
            val reachableResponse = callDomain("bad.host.example.com")

            assertThat(reachableResponse).isUnreachable()
        }
    }
}
