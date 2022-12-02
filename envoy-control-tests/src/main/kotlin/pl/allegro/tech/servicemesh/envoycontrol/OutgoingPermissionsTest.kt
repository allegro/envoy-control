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
            "envoy-control.envoy.snapshot.outgoing-permissions.enabled" to true,
            "envoy-control.envoy.snapshot.egress.domains" to mutableListOf(".test.domain", ".domain")
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
            "envoy-control.envoy.snapshot.outgoing-permissions.enabled" to true,
            "envoy-control.envoy.snapshot.egress.domains" to mutableListOf(".test.domain", ".domain")
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
        consul().server.operations.registerService(service(), name = "tag-service-A", tags = listOf("scope"))
        consul().server.operations.registerService(service(), name = "tag-service-B", tags = listOf("scope"))

        untilAsserted {
            // when
            val unreachableResponse = envoy().egressOperations.callService("not-accessible")
            val unregisteredResponse = envoy().egressOperations.callService("unregistered")
            val reachableResponse = envoy().egressOperations.callService("echo")
            val reachableResponseEchoWithDomain = envoy().egressOperations.callService("echo.test.domain")
            val reachableResponseEchoWithDomain2 = envoy().egressOperations.callService("echo.domain")
            val reachableDomainResponse = envoy().egressOperations.callDomain("www.example.com")
            val unreachableDomainResponse = envoy().egressOperations.callDomain("www.another-example.com")
            val reachableFirstTagResponse = envoy().egressOperations.callService("tag-service-A")
            val reachableSecondTagResponse = envoy().egressOperations.callService("tag-service-B")

            // then
            assertThat(reachableResponse).isOk().isFrom(service())
            assertThat(reachableDomainResponse).isOk()
            assertThat(reachableResponseEchoWithDomain).isOk().isFrom(service())
            assertThat(reachableResponseEchoWithDomain2).isOk().isFrom(service())
            assertThat(unreachableDomainResponse).isUnreachable()
            assertThat(unreachableResponse).isUnreachable()
            assertThat(unregisteredResponse).isUnreachable()
            assertThat(reachableFirstTagResponse).isOk().isFrom(service())
            assertThat(reachableSecondTagResponse).isOk().isFrom(service())
        }
    }
}
