package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.fail
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.RandomConfigFile
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.CallStats
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension
import java.time.Duration

class RoutingPolicyTest {

    companion object {
        private val properties = mapOf(
            "envoy-control.envoy.snapshot.routing.service-tags.enabled" to true,
            "envoy-control.envoy.snapshot.routing.service-tags.metadata-key" to "tag",
        )

        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(consul, properties)

        @JvmField
        @RegisterExtension
        val loremEchoService = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val ipsumEchoService = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val otherEchoService = EchoServiceExtension()

        // language=yaml
        private var autoServiceTagEnabledSettings = """
            node:
              metadata:
                proxy_settings:
                  outgoing:
                    routingPolicy:
                      autoServiceTag: true
                      serviceTagPreference: ["ipsum", "lorem"]
                    dependencies:
                      - service: "echo" 
        """.trimIndent()

        @JvmField
        @RegisterExtension
        val autoServiceTagEnabledEnvoy =
            EnvoyExtension(envoyControl, config = RandomConfigFile.copy(configOverride = autoServiceTagEnabledSettings))

        @JvmField
        @RegisterExtension
        val autoServiceTagDisabledEnvoy = EnvoyExtension(envoyControl)
    }

    @Test
    fun `should filter service instances according to tag preference`() {
        // given
        waitForEcConsulStateSynchronized(listOf(
            consul.server.operations.registerService(
                name = "echo",
                extension = ipsumEchoService,
                tags = listOf("ipsum", "other")
            ),
            consul.server.operations.registerService(
                name = "echo",
                extension = loremEchoService,
                tags = listOf("lorem")
            ),
            consul.server.operations.registerService(name = "echo", extension = otherEchoService, tags = emptyList()),
        ))
        waitForEndpointReady("echo", ipsumEchoService, autoServiceTagEnabledEnvoy)

        // when
        val stats = callEchoTenTimes(autoServiceTagEnabledEnvoy)

        // then
        assertThat(stats.totalHits).isEqualTo(10)
        assertThat(stats.hits(ipsumEchoService)).isEqualTo(10)
        assertThat(stats.hits(loremEchoService)).isEqualTo(0)
        assertThat(stats.hits(otherEchoService)).isEqualTo(0)
    }

    @Test
    fun `should not filter service instances if autoServiceTag is false`() {
        // given
        waitForEcConsulStateSynchronized(listOf(
            consul.server.operations.registerService(
                name = "echo",
                extension = ipsumEchoService,
                tags = listOf("ipsum", "other")
            ),
            consul.server.operations.registerService(
                name = "echo",
                extension = loremEchoService,
                tags = listOf("lorem")
            ),
            consul.server.operations.registerService(name = "echo", extension = otherEchoService, tags = emptyList()),
        ))
        waitForEndpointReady("echo", ipsumEchoService, autoServiceTagEnabledEnvoy)

        // when
        val stats = callEchoTenTimes(autoServiceTagDisabledEnvoy)

        // then
        assertThat(stats.totalHits).isEqualTo(10)
        assertThat(stats.hits(ipsumEchoService)).isBetween(3, 4)
        assertThat(stats.hits(loremEchoService)).isBetween(3, 4)
        assertThat(stats.hits(otherEchoService)).isBetween(3, 4)
    }

    @Test
    fun `should change routing when instance with prefered tag appears`() {
        // given
        val otherEchoId = consul.server.operations.registerService(
            name = "echo", extension = otherEchoService, tags = emptyList())
        waitForEcConsulStateSynchronized(listOf(otherEchoId))
        waitForEndpointReady("echo", otherEchoService, autoServiceTagDisabledEnvoy)

        // when
        val statsAutoServiceTagAfterOther = callEchoTenTimes(autoServiceTagEnabledEnvoy, assertNoErrors = false)
        val statsNoAutoServiceTagAfterOther = callEchoTenTimes(autoServiceTagDisabledEnvoy)

        // then
        statsAutoServiceTagAfterOther.let { stats ->
            assertThat(stats.totalHits).isEqualTo(10)
            assertThat(stats.failedHits).isEqualTo(10)
        }
        statsNoAutoServiceTagAfterOther.let { stats ->
            assertThat(stats.totalHits).isEqualTo(10)
            assertThat(stats.hits(otherEchoService)).isEqualTo(10)
        }

        // when
        val loremEchoId = consul.server.operations.registerService(
            name = "echo", extension = loremEchoService, tags = listOf("lorem"))
        waitForEcConsulStateSynchronized(listOf(otherEchoId, loremEchoId))
        waitForEndpointReady("echo", loremEchoService, autoServiceTagDisabledEnvoy)
        waitForEndpointReady("echo", loremEchoService, autoServiceTagEnabledEnvoy)

        // and
        val statsAutoServiceTagAfterLorem = callEchoTenTimes(autoServiceTagEnabledEnvoy)
        val statsNoAutoServiceTagAfterLorem = callEchoTenTimes(autoServiceTagDisabledEnvoy)

        // then
        statsAutoServiceTagAfterLorem.let { stats ->
            assertThat(stats.totalHits).isEqualTo(10)
            assertThat(stats.hits(loremEchoService)).isEqualTo(10)
            assertThat(stats.hits(otherEchoService)).isEqualTo(0)
        }
        statsNoAutoServiceTagAfterLorem.let { stats ->
            assertThat(stats.totalHits).isEqualTo(10)
            assertThat(stats.hits(loremEchoService)).isEqualTo(5)
            assertThat(stats.hits(otherEchoService)).isEqualTo(5)
        }

        // when
        val ipsumEchoId = consul.server.operations.registerService(
            name = "echo", extension = ipsumEchoService, tags = listOf("noise", "ipsum"))
        waitForEcConsulStateSynchronized(listOf(otherEchoId, loremEchoId, ipsumEchoId))
        waitForEndpointReady("echo", ipsumEchoService, autoServiceTagDisabledEnvoy)
        waitForEndpointReady("echo", ipsumEchoService, autoServiceTagEnabledEnvoy)

        // and
        val statsAutoServiceTagAfterIpsum = callEchoTenTimes(autoServiceTagEnabledEnvoy)
        val statsNoAutoServiceTagAfterIpsum = callEchoTenTimes(autoServiceTagDisabledEnvoy)

        // then
        statsAutoServiceTagAfterIpsum.let { stats ->
            assertThat(stats.totalHits).isEqualTo(10)
            assertThat(stats.hits(ipsumEchoService)).isEqualTo(10)
            assertThat(stats.hits(loremEchoService)).isEqualTo(0)
            assertThat(stats.hits(otherEchoService)).isEqualTo(0)
        }
        statsNoAutoServiceTagAfterIpsum.let { stats ->
            assertThat(stats.totalHits).isEqualTo(10)
            assertThat(stats.hits(ipsumEchoService)).isBetween(3, 4)
            assertThat(stats.hits(loremEchoService)).isBetween(3, 4)
            assertThat(stats.hits(otherEchoService)).isBetween(3, 4)
        }
    }

    @Test
    fun `should change routing when instance with prefered tag disappers`() {
        fail("not implemented")
    }

    private fun waitForEcConsulStateSynchronized(expectedInstancesIds: Collection<String>) {
        untilAsserted(wait = Duration.ofSeconds(5)) {
            val echoInstances = envoyControl.app.getState()["echo"]?.instances.orEmpty()
            assertThat(echoInstances.map { it.id }.toSet())
                .withFailMessage(
                    "EC instances state of 'echo' service not consistent with consul. Expected instances: %s, Found: %s",
                    expectedInstancesIds, echoInstances
                )
                .isEqualTo(expectedInstancesIds.toSet())
        }
    }

    private fun waitForEndpointReady(
        serviceName: String,
        serviceInstance: EchoServiceExtension,
        envoy: EnvoyExtension
    ) {
        untilAsserted(wait = Duration.ofSeconds(5)) {
            assertThat(envoy.container.admin().isEndpointHealthy(serviceName, serviceInstance.container().ipAddress()))
                .withFailMessage {
                    "Expected to see healthy endpoint of cluster '$serviceName' with address " +
                        "'${serviceInstance.container().address()}' in envoy " +
                        "${serviceInstance.container().address()}/clusters, " +
                        "but it's not present. Found following endpoints: " +
                        "${envoy.container.admin().endpointsAddress(serviceName)}"
                }
                .isTrue()
        }
    }

    private fun callStats() = CallStats(listOf(ipsumEchoService, loremEchoService, otherEchoService))

    private fun callEchoTenTimes(envoy: EnvoyExtension, assertNoErrors: Boolean = true): CallStats {
        val stats = callStats()
        envoy.egressOperations.callServiceRepeatedly(
            service = "echo",
            stats = stats,
            maxRepeat = 10,
            assertNoErrors = assertNoErrors
        )
        return stats
    }
}
