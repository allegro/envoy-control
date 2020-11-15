package pl.allegro.tech.servicemesh.envoycontrol.ssl

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isOk
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isUnreachable
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.GenericServiceExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.HttpsEchoContainer

class EnvoySANValidationTest {
    companion object {
        private val properties = mapOf(
                "envoy-control.envoy.snapshot.trustedCaFile" to "/app/root-ca.crt"
        )

        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(consul, properties)

        @JvmField
        @RegisterExtension
        val httpsService = GenericServiceExtension(HttpsEchoContainer())

        @JvmField
        @RegisterExtension
        val envoy = EnvoyExtension(envoyControl, httpsService)

        @JvmStatic
        @BeforeAll
        fun setup() {
            envoy.container.addHost("my.example.com", httpsService.container().ipAddress())
            envoy.container.addHost("bad.host.example.com", httpsService.container().ipAddress())
        }

        @JvmStatic
        @AfterAll
        fun teardown() {
            envoy.container.removeHost("my.example.com")
            envoy.container.removeHost("bad.host.example.com")
        }

    }

    @Test
    fun `should validate SAN part of certificate`() {
        untilAsserted {
            // when
            val reachableResponse = envoy.egressOperations.callDomain("my.example.com")

            assertThat(reachableResponse).isOk()
        }
    }

    @Test
    fun `should reject certificate without matching SAN`() {
        untilAsserted {
            // when
            val response = envoy.egressOperations.callDomain("bad.host.example.com")

            val sanFailCount = envoy.container.admin().statValue(
                    "cluster.bad_host_example_com_443.ssl.fail_verify_san"
            )?.toInt()
            assertThat(sanFailCount).isGreaterThan(0)
            assertThat(response).isUnreachable()
        }
    }
}
