package pl.allegro.tech.servicemesh.envoycontrol.ssl

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlRunnerTestApp
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration
import pl.allegro.tech.servicemesh.envoycontrol.config.echo.HttpsEchoContainer
import pl.allegro.tech.servicemesh.envoycontrol.config.echo.HttpsEchoResponse
import pl.allegro.tech.servicemesh.envoycontrol.config.echo.hasSNI

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

// TODO(https://github.com/allegro/envoy-control/issues/97) - remove when envoy < 1.14.0-dev will be not supported
class EnvoyCompatibleVersionHttpsDependencyTest : EnvoyHttpsDependencyTest() {
    companion object {
        @JvmStatic
        @BeforeAll
        fun setupTest() {
            setup(
                appFactoryForEc1 = { consulPort -> EnvoyControlRunnerTestApp(properties, consulPort) },
                // 1.13.0-dev
                envoyImage = "envoyproxy/envoy-alpine-dev:b7bef67c256090919a4585a1a06c42f15d640a09"
            )
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
