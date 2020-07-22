package pl.allegro.tech.servicemesh.envoycontrol.reliability

import okhttp3.Response
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.fail
import org.assertj.core.api.ObjectAssert
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.config.AdsAllDependencies
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlRunnerTestApp
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration
import pl.allegro.tech.servicemesh.envoycontrol.config.echo.EchoContainer
import pl.allegro.tech.servicemesh.envoycontrol.logger
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.atomic.LongAdder
import kotlin.random.Random

@Tag("reliability")
class ConsulInstancePropagationTest : EnvoyControlTestConfiguration() {

    companion object {
        private val logger by logger()

        private const val verificationTimes = 1
        private const val services = 20
        private const val repeatScenarios = 10

        @JvmStatic
        @BeforeAll
        fun setupPropagationTest() {
            setup(
                envoyConfig = AdsAllDependencies,
                appFactoryForEc1 = { consulPort ->
                    EnvoyControlRunnerTestApp(
                        properties = mapOf(
                            "envoy-control.envoy.snapshot.stateSampleDuration" to Duration.ofSeconds(0),
                            "envoy-control.envoy.snapshot.outgoing-permissions.servicesAllowedToUseWildcard" to "test-service"
                        ),
                        consulPort = consulPort
                    )
                }
            )
        }
    }

    /**
     * This test is meant to stress test the instance propagation from Consul to Envoy.
     * To do the stress test, bump the parameters to higher values ex. services = 250, repeatScenarios = 50.
     */
    @Test
    fun `should test multiple services propagation`() {
        // given
        val runCount = LongAdder()
        val threadPool = Executors.newFixedThreadPool(services)

        // when
        val futures = (1..services).map {
            threadPool.submit { runAllScenarios(runCount) }
        }

        // then
        try {
            futures.map { it.get() }
        } catch (e: Exception) {
            fail<String>("Error running scenarios ${e.message}")
        } finally {
            assertThat(runCount.sum().toInt()).isEqualTo(services * repeatScenarios)
        }
    }

    private fun runAllScenarios(runCount: LongAdder) {
        val scenarios = Scenarios("echo-" + Random.nextInt(10_000_000).toString())
        repeat(repeatScenarios) {
            Thread.sleep(Random.nextLong(1000))
            logger.info("Running scenarios for ${scenarios.serviceName} for ${it + 1} time")
            with(scenarios) {
                try {
                    spawnFirstInstance()
                    spawnSecondInstance()
                    destroySecondInstance()
                    destroyLastInstance()
                } catch (e: Throwable) {
                    logger.error("Error while running scenario", e)
                    throw e
                }
            }
            runCount.increment()
        }
    }

    inner class Scenarios(val serviceName: String) {
        var firstInstanceId: String? = null
        var secondInstanceId: String? = null

        fun spawnFirstInstance() {
            firstInstanceId = registerService(
                id = "$serviceName-1",
                name = serviceName,
                container = echoContainer
            )
            waitForEchosInAdmin(echoContainer)
            repeat(verificationTimes) {
                callService(serviceName).use {
                    assertThat(it).isOk().isFrom(echoContainer)
                }
            }
        }

        fun spawnSecondInstance() {
            secondInstanceId = registerService(
                id = "$serviceName-2",
                name = serviceName,
                container = echoContainer2
            )
            waitForEchosInAdmin(echoContainer, echoContainer2)
            repeat(verificationTimes) {
                callService(serviceName).use {
                    assertThat(it).isOk().isEitherFrom(echoContainer, echoContainer2)
                }
            }
        }

        fun destroySecondInstance() {
            deregisterService(secondInstanceId!!)
            waitForEchosInAdmin(echoContainer)
            repeat(verificationTimes) {
                callService(serviceName).use {
                    assertThat(it).isOk().isFrom(echoContainer)
                }
            }
        }

        fun destroyLastInstance() {
            deregisterService(firstInstanceId!!)
            waitForEchosInAdmin()
            repeat(verificationTimes) {
                callService(serviceName).use {
                    assertThat(it).isUnreachable()
                }
            }
        }

        private val admin = envoyContainer1.admin()

        private fun waitForEchosInAdmin(vararg containers: EchoContainer) {
            untilAsserted {
                val addresses = admin
                    .endpointsAddress(clusterName = serviceName)
                    .map { "${it.address}:${it.portValue}" }
                assertThat(addresses)
                    .hasSize(containers.size)
                    .containsExactlyInAnyOrderElementsOf(containers.map { it.address() })
            }
        }
    }

    fun ObjectAssert<Response>.isEitherFrom(vararg echoContainers: EchoContainer): ObjectAssert<Response> {
        matches {
            val serviceResponse = it.body()?.string() ?: ""
            echoContainers.any { container -> serviceResponse.contains(container.response) }
        }
        return this
    }
}
