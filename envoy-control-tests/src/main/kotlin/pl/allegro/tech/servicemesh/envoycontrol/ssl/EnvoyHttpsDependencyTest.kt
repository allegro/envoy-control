package pl.allegro.tech.servicemesh.envoycontrol.ssl

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.hasSNI
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isOk
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.GenericServiceExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.HttpsEchoContainer
import pl.allegro.tech.servicemesh.envoycontrol.config.service.HttpsEchoResponse

class EnvoyHttpsDependencyTest {
    companion object {
        @JvmStatic
        protected val properties = mapOf(
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
        }

        @JvmStatic
        @AfterAll
        fun teardown() {
            envoy.container.removeHost("my.example.com")
        }
    }

    @Test
    fun `should include SNI in request to upstream`() {
        // when
        val response = untilAsserted {
            val response = envoy.egressOperations.callDomain("my.example.com")

            assertThat(response).isOk()
            response
        }

        // then
        assertThat(HttpsEchoResponse(response)).hasSNI("my.example.com")
    }
}
