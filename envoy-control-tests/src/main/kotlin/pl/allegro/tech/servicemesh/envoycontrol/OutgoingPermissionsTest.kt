package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isFrom
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isOk
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isUnreachable
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.Ads
import pl.allegro.tech.servicemesh.envoycontrol.config.Xds
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension

class AdsOutgoingPermissionsTest : OutgoingPermissionsTest {

    companion object {

        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(consul, mapOf(
            "envoy-control.envoy.snapshot.outgoing-permissions.enabled" to true
        ))

        @JvmField
        @RegisterExtension
        val service = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val envoy = EnvoyExtension(envoyControl, config = Ads)
    }

    override fun consul() = consul

    override fun service() = service

    override fun envoy() = envoy
}

class XdsOutgoingPermissionsTest : OutgoingPermissionsTest {

    companion object {

        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(consul, mapOf(
            "envoy-control.envoy.snapshot.outgoing-permissions.enabled" to true
        ))

        @JvmField
        @RegisterExtension
        val service = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val envoy = EnvoyExtension(envoyControl, config = Xds)
    }

    override fun consul() = consul

    override fun service() = service

    override fun envoy() = envoy
}

interface OutgoingPermissionsTest {

    fun consul(): ConsulExtension

    fun service(): EchoServiceExtension

    fun envoy(): EnvoyExtension

    @Test
    fun `should only allow access to resources from node_metadata_dependencies`() {
        // given
        consul().server.operations.registerService(service(), name = "not-accessible")
        consul().server.operations.registerService(service(), name = "echo")

        untilAsserted {
            // when
            val unreachableResponse = envoy().egressOperations.callService("not-accessible")
            val unregisteredResponse = envoy().egressOperations.callService("unregistered")
            val reachableResponse = envoy().egressOperations.callService("echo")
            val reachableDomainResponse = envoy().egressOperations.callDomain("www.example.com")
            val unreachableDomainResponse = envoy().egressOperations.callDomain("www.another-example.com")

            // then
            assertThat(reachableResponse).isOk().isFrom(service())
            assertThat(reachableDomainResponse).isOk()
            assertThat(unreachableDomainResponse).isUnreachable()
            assertThat(unreachableResponse).isUnreachable()
            assertThat(unregisteredResponse).isUnreachable()
        }
    }
}
