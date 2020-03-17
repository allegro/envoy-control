package pl.allegro.tech.servicemesh.envoycontrol.ssl

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlRunnerTestApp
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration
import pl.allegro.tech.servicemesh.envoycontrol.config.containers.HttpsEchoContainer

class EnvoySANValidationTest : EnvoyControlTestConfiguration() {
    companion object {
        private val properties = mapOf(
                "envoy-control.envoy.snapshot.trustedCaFile" to "/usr/local/share/ca-certificates/root-ca.crt"
        )
        val httpsEchoContainer = HttpsEchoContainer()

        @JvmStatic
        @BeforeAll
        fun setupTest() {
            setup(appFactoryForEc1 = { consulPort -> EnvoyControlRunnerTestApp(properties, consulPort) })
            httpsEchoContainer.start()
            envoyContainer1.addHost("my.example.com", httpsEchoContainer.ipAddress())
            envoyContainer1.addHost("bad.host.example.com", httpsEchoContainer.ipAddress())
        }

        @JvmStatic
        @AfterAll
        fun teardown() {
            httpsEchoContainer.stop()
        }
    }

    @Test
    fun `should validate SAN part of certificate`() {
        untilAsserted {
            // when
            val reachableResponse = callDomain("my.example.com")

            assertThat(reachableResponse).isOk()
        }
    }

    @Test
    fun `should reject certificate without matching SAN`() {
        untilAsserted {
            // when
            val response = callDomain("bad.host.example.com")

            val sanFailCount = envoyContainer1.admin().statValue(
                    "cluster.bad_host_example_com_443.ssl.fail_verify_san"
            )?.toInt()
            assertThat(sanFailCount).isGreaterThan(0)
            assertThat(response).isUnreachable()
        }
    }
}
