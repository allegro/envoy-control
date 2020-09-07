package pl.allegro.tech.servicemesh.envoycontrol.reliability

import com.ecwid.consul.v1.OperationException
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Duration
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlRunnerTestApp
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulOperations
import pl.allegro.tech.servicemesh.envoycontrol.reliability.Toxiproxy.Companion.externalEnvoyControl1GrpcPort
import pl.allegro.tech.servicemesh.envoycontrol.reliability.Toxiproxy.Companion.toxiproxyGrpcPort

@Suppress("SwallowedException")
internal class ConsulRpcLimitReachedTest : ReliabilityTest() {

    companion object {
        @JvmStatic
        @BeforeAll
        fun setup() {
            setup(
                appFactoryForEc1 = {
                    EnvoyControlRunnerTestApp(
                        consulPort = lowRpcConsulClient.port,
                        grpcPort = toxiproxyGrpcPort
                    )
                },
                envoyConnectGrpcPort = externalEnvoyControl1GrpcPort
            )
        }
    }

    @Test
    fun `is resilient to ECs consul client reaching RPC limit`() {
        // given
        // if failureDuration is Duration(1, SECONDS).divide(2) then Duration(0, SECONDS)
        registerEchoInOtherAgentAfter(failureDuration.divide(2L))

        // when
        holdAssertionsTrue(interval = Duration.ONE_SECOND) {
            rpcLimitReached()
        }

        // then
        assertEchoReachableThroughProxy()
    }

    private fun assertEchoReachableThroughProxy() {
        untilAsserted(wait = defaultDuration.multiply(2L)) {
            callService("echo").use {
                assertThat(it).isOk().isFrom(echoContainer)
            }
        }
    }

    private fun registerEchoInOtherAgentAfter(time: Duration) {
        Thread {
            Thread.sleep(time.valueInMS)
            registerService(name = "echo")
        }.start()
    }

    private fun rpcLimitReached() {
        untilAsserted {
            val limitReached = burstRpcLimit(lowRpcConsulClient.operations)
            assertThat(limitReached).isEqualTo(true)
        }
    }

    private fun burstRpcLimit(consulOperations: ConsulOperations): Boolean {
        var limitReached = false
        repeat(5) {
            try {
                consulOperations.anyRpcOperation()
            } catch (e: OperationException) {
                limitReached = true
            }
        }

        return limitReached
    }
}
