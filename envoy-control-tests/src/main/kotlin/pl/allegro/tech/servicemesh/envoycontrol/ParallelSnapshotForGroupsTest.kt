package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.config.Ads
import pl.allegro.tech.servicemesh.envoycontrol.config.AdsWithDisabledEndpointPermissions
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlRunnerTestApp
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration

class ParallelSnapshotForGroupsTest : EnvoyControlTestConfiguration() {

    companion object {
        private val properties = mapOf(
            "envoy-control.server.group-snapshot-update-scheduler.type" to "PARALLEL",
            "envoy-control.server.group-snapshot-update-scheduler.parallel-pool-size" to 3,
            "logging.level.pl.allegro.tech.servicemesh.envoycontrol.server.callbacks" to "DEBUG"
        )

        @JvmStatic
        @BeforeAll
        fun setupTest() {
            setup(
                appFactoryForEc1 = { consulPort ->
                    EnvoyControlRunnerTestApp(properties = properties, consulPort = consulPort)
                },
                envoys = 2,
                envoyConfig = Ads,
                // we need different config for second envoy to ensure we have two cache groups
                secondEnvoyConfig = AdsWithDisabledEndpointPermissions
            )
        }
    }

    @Test
    fun `should update multiple envoy's configs in PARALLEL mode`() {
        // when
        registerService(name = "echo1")

        // then
        untilAsserted {
            callService(service = "echo1", address = envoyContainer1.egressListenerUrl()).also {
                assertThat(it).isOk().isFrom(echoContainer)
            }
            callService(service = "echo1", address = envoyContainer2.egressListenerUrl()).also {
                assertThat(it).isOk().isFrom(echoContainer)
            }
        }
    }
}
