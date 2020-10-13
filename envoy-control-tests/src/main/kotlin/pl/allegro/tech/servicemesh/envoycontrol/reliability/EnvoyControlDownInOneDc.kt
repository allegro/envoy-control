package pl.allegro.tech.servicemesh.envoycontrol.reliability

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlRunnerTestApp
import pl.allegro.tech.servicemesh.envoycontrol.reliability.Toxiproxy.Companion.ec1HttpPort
import pl.allegro.tech.servicemesh.envoycontrol.reliability.Toxiproxy.Companion.ec2HttpPort
import pl.allegro.tech.servicemesh.envoycontrol.reliability.Toxiproxy.Companion.externalEnvoyControl1HttpPort
import pl.allegro.tech.servicemesh.envoycontrol.reliability.Toxiproxy.Companion.externalEnvoyControl2HttpPort

internal class EnvoyControlDownInOneDc : ReliabilityTest() {

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
                        consulPort = consulHttpPort,
                        properties = properties,
                        appPort = ec1HttpPort
                    )
                },
                appFactoryForEc2 = {
                    EnvoyControlRunnerTestApp(
                        consulPort = consul2HttpPort,
                        appPort = ec2HttpPort
                    )
                },
                ec1RegisterPort = externalEnvoyControl1HttpPort,
                ec2RegisterPort = externalEnvoyControl2HttpPort
            )
        }
    }

    @Test
    fun `should be resilient to transient unavailability of EC in one DC`() {
        // given
        val id = registerServiceInRemoteCluster("echo", echoContainer)

        // then
        waitUntilEchoCalledThroughEnvoyResponds(echoContainer)

        // when
        cutOffConnectionBetweenECs()
        makeChangesInRemoteDcAsynchronously(id)

        // then
        holdAssertionsTrue {
            assertReachableThroughEnvoy("echo")
        }

        // when
        restoreConnectionBetweenECs()

        // then
        waitUntilEchoCalledThroughEnvoyResponds(echoContainer2)
    }

    private fun makeChangesInRemoteDcAsynchronously(id: String) {
        Thread {
            // if failureDuration is Duration(1, SECONDS).divide(2) then Duration(0, SECONDS)
            Thread.sleep(failureDuration.dividedBy(2L).toMillis())
            deregisterServiceInRemoteDc(id)
            registerServiceInRemoteCluster("echo", echoContainer2)
        }.start()
    }
}
