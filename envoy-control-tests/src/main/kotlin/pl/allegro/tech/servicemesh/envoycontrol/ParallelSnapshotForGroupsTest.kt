package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isFrom
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isOk
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.Ads
import pl.allegro.tech.servicemesh.envoycontrol.config.AdsWithDisabledEndpointPermissions
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.echo.EchoServiceExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension

class ParallelSnapshotForGroupsTest {

    companion object {

        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(consul, mapOf(
            "envoy-control.server.group-snapshot-update-scheduler.type" to "PARALLEL",
            "envoy-control.server.group-snapshot-update-scheduler.parallel-pool-size" to 3,
            "logging.level.pl.allegro.tech.servicemesh.envoycontrol.server.callbacks" to "DEBUG"
        ))

        @JvmField
        @RegisterExtension
        val service = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val envoy = EnvoyExtension(envoyControl, config = Ads)

        @JvmField
        @RegisterExtension
        val secondEnvoy = EnvoyExtension(envoyControl, config = AdsWithDisabledEndpointPermissions)
    }

    @Test
    fun `should update multiple envoy's configs in PARALLEL mode`() {
        // when
        consul.server.operations.registerService(service, name = "echo")

        // then
        untilAsserted {
            envoy.egressOperations.callService(service = "echo").also {
                assertThat(it).isOk().isFrom(service)
            }
            secondEnvoy.egressOperations.callService(service = "echo").also {
                assertThat(it).isOk().isFrom(service)
            }
        }
    }
}
