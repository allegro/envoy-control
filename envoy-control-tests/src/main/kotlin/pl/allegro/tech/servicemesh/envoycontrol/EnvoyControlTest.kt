package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isFrom
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isOk
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.Ads
import pl.allegro.tech.servicemesh.envoycontrol.config.AdsWithStaticListeners
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension

class AdsWithStaticListenersEnvoyControlTest : EnvoyControlTest {
    companion object {

        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(consul)

        @JvmField
        @RegisterExtension
        val service = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val redeployedService = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val envoy = EnvoyExtension(envoyControl, config = AdsWithStaticListeners)
    }

    override fun consul() = consul

    override fun envoyControl() = envoyControl

    override fun service() = service

    override fun redeployedService() = redeployedService

    override fun envoy() = envoy
}

class AdsEnvoyControlTest : EnvoyControlTest {
    companion object {

        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(consul)

        @JvmField
        @RegisterExtension
        val service = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val redeployedService = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val envoy = EnvoyExtension(envoyControl, config = Ads)
    }

    override fun consul() = consul

    override fun envoyControl() = envoyControl

    override fun service() = service

    override fun redeployedService() = redeployedService

    override fun envoy() = envoy
}

interface EnvoyControlTest {

    fun consul(): ConsulExtension

    fun envoyControl(): EnvoyControlExtension

    fun service(): EchoServiceExtension

    fun redeployedService(): EchoServiceExtension

    fun envoy(): EnvoyExtension

    @Test
    fun `should allow proxy-ing request using envoy`() {
        // given
        consul().server.operations.registerService(service(), name = "echo")

        untilAsserted {
            // when
            val response = envoy().egressOperations.callService("echo")

            // then
            assertThat(response).isOk().isFrom(service())
        }
    }

    @Test
    fun `should route traffic to the second instance when first is deregistered`() {
        // given
        val id = consul().server.operations.registerService(service(), name = "echo")

        untilAsserted {
            val response = envoy().egressOperations.callService("echo")
            assertThat(response).isOk().isFrom(service())
        }

        // when
        redeployEchoService(id)

        // then
        untilAsserted {
            val response = envoy().egressOperations.callService(service = "echo")
            assertThat(response).isOk().isFrom(redeployedService())
        }
    }

    @Test
    fun `should assign endpoints to correct clusters`() {
        // given
        consul().server.operations.registerService(service(), name = "echo")

        untilAsserted {
            // when
            val adminInstance =
                envoy().container.admin().cluster(cluster = "echo", ip = service().container().ipAddress())

            // then
            assertThat(adminInstance).isNotNull
            assertThat(adminInstance!!.cluster).isEqualTo("dc1")
        }
    }

    fun redeployEchoService(id: String) {
        // we first register a new instance and then remove other to maintain cluster presence in Envoy
        consul().server.operations.registerService(redeployedService(), name = "echo")
        waitForEchoServices(instances = 2)

        consul().server.operations.deregisterService(id)
        waitForEchoServices(instances = 1)
    }

    private fun waitForEchoServices(instances: Int) {
        untilAsserted {
            assertThat(envoy().container.admin().numOfEndpoints(clusterName = "echo"))
                .isEqualTo(instances)
        }
    }
}
