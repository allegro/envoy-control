package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.config.Ads
import pl.allegro.tech.servicemesh.envoycontrol.config.AdsWithNoDependencies
import pl.allegro.tech.servicemesh.envoycontrol.config.AdsWithStaticListeners
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration
import pl.allegro.tech.servicemesh.envoycontrol.config.Xds

internal class AdsWithNoDependenciesEnvoyControlSmokeTest : EnvoyControlSmokeTest() {
    companion object {

        @JvmStatic
        @BeforeAll
        fun adsSetup() {
            setup(envoy1Config = AdsWithNoDependencies)
        }
    }
}

internal class AdsWithStaticListenersEnvoyControlSmokeTest : EnvoyControlSmokeTest() {
    companion object {

        @JvmStatic
        @BeforeAll
        fun adsSetup() {
            setup(envoy1Config = AdsWithStaticListeners)
        }
    }
}

internal class AdsEnvoyControlSmokeTest : EnvoyControlSmokeTest() {
    companion object {

        @JvmStatic
        @BeforeAll
        fun adsSetup() {
            setup(envoy1Config = Ads)
        }
    }
}

internal class XdsEnvoyControlSmokeTest : EnvoyControlSmokeTest() {
    companion object {

        @JvmStatic
        @BeforeAll
        fun nonAdsSetup() {
            setup(envoy1Config = Xds)
        }
    }
}

internal abstract class EnvoyControlSmokeTest : EnvoyControlTestConfiguration() {
    @Test
    fun `should create a server listening on a port`() {
        untilAsserted {
            // when
            val ingressRoot = callIngressRoot()

            // then
            assertThat(ingressRoot.code()).isEqualTo(200)
        }
    }
}
