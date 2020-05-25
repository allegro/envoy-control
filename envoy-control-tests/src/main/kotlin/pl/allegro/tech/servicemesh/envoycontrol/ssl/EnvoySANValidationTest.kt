package pl.allegro.tech.servicemesh.envoycontrol.ssl

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo1EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlRunnerTestApp
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration
import pl.allegro.tech.servicemesh.envoycontrol.config.containers.HttpsEchoContainer
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyContainer

class EnvoySANValidationTest : EnvoyControlTestConfiguration() {
    companion object {
        private val properties = mapOf(
                "envoy-control.envoy.snapshot.trustedCaFile" to "/app/root-ca.crt"
        )
        val httpsEchoContainer = HttpsEchoContainer()
        lateinit var envoy1SanExampleCom: EnvoyContainer

        @JvmStatic
        @BeforeAll
        fun setupTest() {
            setup(appFactoryForEc1 = { consulPort -> EnvoyControlRunnerTestApp(properties, consulPort) })
            envoy1SanExampleCom = EnvoyContainer(
                Echo1EnvoyAuthConfig.filePath,
                localServiceContainer.ipAddress(),
                envoyControl1.grpcPort,
                image = defaultEnvoyImage,
                certificateChain = "/app/fullchain.pem",
                trustedCa = "/app/root-ca.crt"
                ).withNetwork(network)
            envoy1SanExampleCom.start()

            httpsEchoContainer.start()
            envoy1SanExampleCom.addHost("my.example.com", httpsEchoContainer.ipAddress())
            envoy1SanExampleCom.addHost("bad.host.example.com", httpsEchoContainer.ipAddress())
        }

        @JvmStatic
        @AfterAll
        fun teardown() {
            httpsEchoContainer.stop()
            envoy1SanExampleCom.stop()
        }
    }

    @Test
    fun `should validate SAN part of certificate`() {
        untilAsserted {
            // when
            val reachableResponse = callDomain("my.example.com", envoy1SanExampleCom.egressListenerUrl())

            assertThat(reachableResponse).isOk()
        }
    }

    @Test
    fun `should reject certificate without matching SAN`() {
        untilAsserted {
            // when
            val response = callDomain("bad.host.example.com", envoy1SanExampleCom.egressListenerUrl())

            val sanFailCount = envoyContainer1.admin().statValue(
                    "cluster.bad_host_example_com_443.ssl.fail_verify_san"
            )?.toInt()
            assertThat(sanFailCount).isGreaterThan(0)
            assertThat(response).isUnreachable()
        }
    }
}
