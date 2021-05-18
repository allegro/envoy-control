package pl.allegro.tech.servicemesh.envoycontrol.reliability

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isEitherFrom
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isFrom
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isOk
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isUnreachable
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.AdsAllDependencies
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulMultiClusterExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlClusteredExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoContainer
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension
import pl.allegro.tech.servicemesh.envoycontrol.logger
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.atomic.LongAdder
import kotlin.random.Random

@Tag("reliability")
class ConsulInstancePropagationTest {

    companion object {
        private val logger by logger()

        private const val verificationTimes = 1
        private const val services = 20
        private const val repeatScenarios = 10

        @JvmField
        @RegisterExtension
        val consulClusters = ConsulMultiClusterExtension()

        val properties = mapOf(
            "envoy-control.envoy.snapshot.stateSampleDuration" to Duration.ofSeconds(0),
            "envoy-control.envoy.snapshot.outgoing-permissions.servicesAllowedToUseWildcard" to "test-service"
        )

        @JvmField
        @RegisterExtension
        val envoyControlDc1 = EnvoyControlClusteredExtension(consulClusters.serverFirst, { properties }, dependencies = listOf(consulClusters))

        @JvmField
        @RegisterExtension
        val envoy = EnvoyExtension(envoyControl = envoyControlDc1, config = AdsAllDependencies)

        @JvmField
        @RegisterExtension
        val firstService = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val secondService = EchoServiceExtension()
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
            Assertions.fail<String>("Error running scenarios ${e.message}")
        } finally {
            Assertions.assertThat(runCount.sum().toInt())
                .isEqualTo(services * repeatScenarios)
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
            firstInstanceId = consulClusters.serverFirst.operations.registerService(
                firstService,
                id = "$serviceName-1",
                name = serviceName
            )

            waitForEchosInAdmin(firstService.container())

            repeat(verificationTimes) {
                envoy.egressOperations.callService(serviceName).also {
                    Assertions.assertThat(it).isOk().isFrom(firstService)
                }
            }
        }

        fun spawnSecondInstance() {
            secondInstanceId = consulClusters.serverFirst.operations.registerService(
                secondService,
                id = "$serviceName-2",
                name = serviceName
            )
            waitForEchosInAdmin(firstService.container(), secondService.container())

            repeat(verificationTimes) {
                envoy.egressOperations.callService(serviceName).also {
                    Assertions.assertThat(it).isOk().isEitherFrom(firstService, secondService)
                }
            }
        }

        fun destroySecondInstance() {
            deregisterService(secondInstanceId!!)
            waitForEchosInAdmin(firstService.container())
            repeat(verificationTimes) {
                envoy.egressOperations.callService(serviceName).also {
                    Assertions.assertThat(it).isOk().isFrom(firstService)
                }
            }
        }

        private fun deregisterService(serviceId: String) {
            consulClusters.serverFirst.operations.deregisterService(serviceId)
        }

        fun destroyLastInstance() {
            deregisterService(firstInstanceId!!)
            waitForEchosInAdmin()
            repeat(verificationTimes) {
                envoy.egressOperations.callService(serviceName).also {
                    Assertions.assertThat(it).isUnreachable()
                }
            }
        }

        private val admin = envoy.container.admin()

        private fun waitForEchosInAdmin(vararg containers: EchoContainer) {
            untilAsserted {
                val addresses = admin
                    .endpointsAddress(clusterName = serviceName)
                    .map { "${it.address}:${it.portValue}" }
                Assertions.assertThat(addresses)
                    .hasSize(containers.size)
                    .containsExactlyInAnyOrderElementsOf(containers.map { it.address() })
            }
        }
    }
}
