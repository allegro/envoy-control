package pl.allegro.tech.servicemesh.envoycontrol.reliability

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlRunnerTestApp
import pl.allegro.tech.servicemesh.envoycontrol.reliability.Toxiproxy.Companion.ec1HttpPort
import pl.allegro.tech.servicemesh.envoycontrol.reliability.Toxiproxy.Companion.ec2HttpPort
import pl.allegro.tech.servicemesh.envoycontrol.reliability.Toxiproxy.Companion.externalEnvoyControl1GrpcPort
import pl.allegro.tech.servicemesh.envoycontrol.reliability.Toxiproxy.Companion.externalEnvoyControl1HttpPort
import pl.allegro.tech.servicemesh.envoycontrol.reliability.Toxiproxy.Companion.externalEnvoyControl2GrpcPort
import pl.allegro.tech.servicemesh.envoycontrol.reliability.Toxiproxy.Companion.externalEnvoyControl2HttpPort
import pl.allegro.tech.servicemesh.envoycontrol.reliability.Toxiproxy.Companion.toxiproxyGrpcPort
import pl.allegro.tech.servicemesh.envoycontrol.reliability.Toxiproxy.Companion.toxiproxyGrpcPort2

internal class EnvoyControlDownInAllDcs : ReliabilityTest() {

    companion object {

        private val properties = mapOf(
            "envoy-control.sync.enabled" to true
        )

        @JvmStatic
        @BeforeAll
        fun setup() {
            setup(
                envoyControls = 2,
                appFactoryForEc1 = {
                    EnvoyControlRunnerTestApp(
                        properties = properties,
                        consulPort = consulHttpPort,
                        appPort = ec1HttpPort,
                        grpcPort = toxiproxyGrpcPort
                    )
                },
                appFactoryForEc2 = {
                    EnvoyControlRunnerTestApp(
                        properties = properties,
                        consulPort = consul2HttpPort,
                        appPort = ec2HttpPort,
                        grpcPort = toxiproxyGrpcPort2
                    )
                },
                ec1RegisterPort = externalEnvoyControl1HttpPort,
                ec2RegisterPort = externalEnvoyControl2HttpPort,
                envoyConnectGrpcPort = externalEnvoyControl1GrpcPort,
                envoyConnectGrpcPort2 = externalEnvoyControl2GrpcPort
            )
        }
    }

    @Test
    fun `should allow to communicate between already known clusters when all ECs are down`() {
        // given
        registerServiceInRemoteDc(name = "service-1")
        assertReachableThroughEnvoy("service-1")

        // when
        makeEnvoyControlUnavailable()
        makeEnvoyControl2Unavailable()

        // and
        registerService(name = "service-2")

        // then
        holdAssertionsTrue {
            assertReachableThroughEnvoy("service-1")
            assertUnreachableThroughEnvoy("service-2")
        }

        // when
        makeEnvoyControlAvailable()
        makeEnvoyControl2Available()

        // then
        assertReachableThroughEnvoy("service-1")
        assertReachableThroughEnvoy("service-2")
    }
}
