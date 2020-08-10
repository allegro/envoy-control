package pl.allegro.tech.servicemesh.envoycontrol.reliability

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlRunnerTestApp
import pl.allegro.tech.servicemesh.envoycontrol.reliability.Toxiproxy.Companion.ec1HttpPort
import pl.allegro.tech.servicemesh.envoycontrol.reliability.Toxiproxy.Companion.ec2HttpPort
import pl.allegro.tech.servicemesh.envoycontrol.reliability.Toxiproxy.Companion.externalEnvoyControl1GrpcPort
import pl.allegro.tech.servicemesh.envoycontrol.reliability.Toxiproxy.Companion.externalEnvoyControl1HttpPort
import pl.allegro.tech.servicemesh.envoycontrol.reliability.Toxiproxy.Companion.externalEnvoyControl2GrpcPort
import pl.allegro.tech.servicemesh.envoycontrol.reliability.Toxiproxy.Companion.externalEnvoyControl2HttpPort
import pl.allegro.tech.servicemesh.envoycontrol.reliability.Toxiproxy.Companion.toxiproxyGrpcPort
import pl.allegro.tech.servicemesh.envoycontrol.reliability.Toxiproxy.Companion.toxiproxyGrpcPort2

internal class EnovyControlInstanceDownInOneDc : ReliabilityTest() {

    companion object {

        @JvmStatic
        @BeforeAll
        fun setup() {
            setup(
                envoyControls = 2,
                appFactoryForEc1 = {
                    EnvoyControlRunnerTestApp(
                        consulPort = consulHttpPort,
                        appPort = ec1HttpPort,
                        grpcPort = toxiproxyGrpcPort
                    )
                },
                appFactoryForEc2 = {
                    EnvoyControlRunnerTestApp(
                        consulPort = consulHttpPort,
                        appPort = ec2HttpPort,
                        grpcPort = toxiproxyGrpcPort2
                    )
                },
                ec1RegisterPort = externalEnvoyControl1HttpPort,
                ec2RegisterPort = externalEnvoyControl2HttpPort,
                envoyConnectGrpcPort = externalEnvoyControl1GrpcPort,
                envoyConnectGrpcPort2 = externalEnvoyControl2GrpcPort,
                instancesInSameDc = true
            )
        }
    }

    @Test
    fun `is resilient to one instance of EnvoyControl failure in same dc`() {
        // given - force envoy to make grpc connection with first EC instance
        makeEnvoyControl2Unavailable()

        // when
        registerService(name = "service-1")

        // then - ensure it has grpc connection by calling service
        assertReachableThroughEnvoy("service-1")

        // when - break grpc connection by making first instance unavailable
        makeEnvoyControlUnavailable()

        // and
        registerService(name = "service-2")

        // then - ensure that there is no grpc connection envoy - ec
        holdAssertionsTrue {
            assertReachableThroughEnvoy("service-1")
            assertUnreachableThroughEnvoy("service-2")
        }

        // when - start second instance of EC
        makeEnvoyControl2Available()

        // then - ensure that envoy connects to second instance
        assertReachableThroughEnvoy("service-2")
    }
}
