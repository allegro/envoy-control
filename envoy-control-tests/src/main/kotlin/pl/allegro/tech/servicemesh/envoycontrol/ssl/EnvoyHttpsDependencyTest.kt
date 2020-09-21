package pl.allegro.tech.servicemesh.envoycontrol.ssl

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlRunnerTestApp
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration
import pl.allegro.tech.servicemesh.envoycontrol.config.service.HttpsEchoContainer
import pl.allegro.tech.servicemesh.envoycontrol.config.service.HttpsEchoResponse
import pl.allegro.tech.servicemesh.envoycontrol.config.service.hasSNI

class EnvoyCurrentVersionHttpsDependencyTest : EnvoyHttpsDependencyTest() {
    companion object {
        @JvmStatic
        @BeforeAll
        fun setupTest() {
            setup(appFactoryForEc1 = { consulPort -> EnvoyControlRunnerTestApp(properties, consulPort) })
            setupTestCommon()
        }
    }
}

abstract class EnvoyHttpsDependencyTest : EnvoyControlTestConfiguration() {
    companion object {
        @JvmStatic
        protected val properties = mapOf(
            "envoy-control.envoy.snapshot.trustedCaFile" to "/app/root-ca.crt"
        )
        val httpsEchoContainer = HttpsEchoContainer()

        @JvmStatic
        fun setupTestCommon() {
            httpsEchoContainer.start()
            envoyContainer1.addHost("my.example.com", httpsEchoContainer.ipAddress())
        }

        @JvmStatic
        @AfterAll
        fun teardown() {
            httpsEchoContainer.stop()
        }
    }

    @Test
    fun `should include SNI in request to upstream`() {
        // when
        val response = untilAsserted {
            val response = callDomain("my.example.com")

            assertThat(response).isOk()
            response
        }

        // then
        assertThat(HttpsEchoResponse(response)).hasSNI("my.example.com")
    }
}
