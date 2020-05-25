package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.config.Ads
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlRunnerTestApp
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration
import pl.allegro.tech.servicemesh.envoycontrol.config.Xds

class AdsOutgoingPermissionsTest : OutgoingPermissionsTest() {
    companion object {

        private val properties = mapOf("envoy-control.envoy.snapshot.outgoing-permissions.enabled" to true)

        @JvmStatic
        @BeforeAll
        fun setupTest() {
            setup(
                envoyConfig = Ads,
                appFactoryForEc1 = { consulPort -> EnvoyControlRunnerTestApp(properties, consulPort) }
            )
        }
    }
}

class XdsOutgoingPermissionsTest : OutgoingPermissionsTest() {
    companion object {

        private val properties = mapOf("envoy-control.envoy.snapshot.outgoing-permissions.enabled" to true)

        @JvmStatic
        @BeforeAll
        fun setupTest() {
            setup(
                envoyConfig = Xds,
                appFactoryForEc1 = { consulPort -> EnvoyControlRunnerTestApp(properties, consulPort) }
            )
        }
    }
}

abstract class OutgoingPermissionsTest : EnvoyControlTestConfiguration() {

    @Test
    fun `should only allow access to resources from node_metadata_dependencies`() {
        // given
        registerService(name = "not-accessible", container = echoContainer)
        registerService(name = "echo1")

        untilAsserted {
            // when
            val unreachableResponse = callService(service = "not-accessible")
            val unregisteredResponse = callService(service = "unregistered")
            val reachableResponse = callEcho()
            val reachableDomainResponse = callDomain("www.example.com")
            val unreachableDomainResponse = callDomain("www.another-example.com")

            // then
            assertThat(reachableResponse).isOk().isFrom(echoContainer)
            assertThat(reachableDomainResponse).isOk()
            assertThat(unreachableDomainResponse).isUnreachable()
            assertThat(unreachableResponse).isUnreachable()
            assertThat(unregisteredResponse).isUnreachable()
        }
    }
}
