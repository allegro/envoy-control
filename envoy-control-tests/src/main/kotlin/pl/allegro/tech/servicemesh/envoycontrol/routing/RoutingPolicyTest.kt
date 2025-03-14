package pl.allegro.tech.servicemesh.envoycontrol.routing

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isFrom
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isOk
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.RandomConfigFile
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.CallStats
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.ResponseWithBody
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension
import pl.allegro.tech.servicemesh.envoycontrol.routing.RoutingPolicyTestBase.Companion.consul
import pl.allegro.tech.servicemesh.envoycontrol.routing.RoutingPolicyTestBase.Companion.ipsumEchoService
import pl.allegro.tech.servicemesh.envoycontrol.routing.RoutingPolicyTestBase.Companion.otherEchoService
import java.time.Duration

abstract class RoutingPolicyTestBase(
    val envoyControl: EnvoyControlExtension,
    private val autoServiceTagEnabledEnvoy: EnvoyExtension,
    private val autoServiceTagDisabledEnvoy: EnvoyExtension
) {

    companion object {

        val properties = mapOf(
            "envoy-control.envoy.snapshot.routing.service-tags.enabled" to true,
            "envoy-control.envoy.snapshot.routing.service-tags.metadata-key" to "tag",
            "envoy-control.envoy.snapshot.routing.service-tags.auto-service-tag-enabled" to true,
            "envoy-control.envoy.snapshot.routing.service-tags.reject-requests-with-duplicated-auto-service-tag" to true
        )

        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

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
        val autoServiceTagEnabledSettings = """
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
    }

    @Test
    fun `should filter service instances according to tag preference`() {
        // given
        waitForEcConsulStateSynchronized(
            listOf(
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
                consul.server.operations.registerService(
                    name = "echo",
                    extension = otherEchoService,
                    tags = emptyList()
                ),
            )
        )
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
    fun `should consider client side service-tag`() {
        // given
        val ipsumBetaEchoService = ipsumEchoService
        val ipsumAlphaEchoService = otherEchoService
        val loremBetaEchoService = loremEchoService

        val ipsumBetaId = consul.server.operations.registerService(
            name = "echo",
            extension = ipsumBetaEchoService,
            tags = listOf("ipsum", "beta")
        )
        val loremBetaId = consul.server.operations.registerService(
            name = "echo",
            extension = loremBetaEchoService,
            tags = listOf("lorem", "beta")
        )
        val ipsumAlphaId = consul.server.operations.registerService(
            name = "echo",
            extension = ipsumAlphaEchoService,
            tags = listOf("ipsum", "alpha")
        )
        waitForEcConsulStateSynchronized(listOf(ipsumBetaId, ipsumAlphaId, loremBetaId))
        waitForEndpointReady("echo", ipsumAlphaEchoService, autoServiceTagEnabledEnvoy)
        waitForEndpointReady("echo", ipsumAlphaEchoService, autoServiceTagDisabledEnvoy)

        // when
        val statsAutoServiceTag = callEchoTenTimes(autoServiceTagEnabledEnvoy, tag = "beta")
        val statsNoAutoServiceTag = callEchoTenTimes(autoServiceTagDisabledEnvoy, tag = "beta")

        // then
        statsAutoServiceTag.let { stats ->
            assertThat(stats.totalHits).isEqualTo(10)
            assertThat(stats.hits(ipsumBetaEchoService)).isEqualTo(10)
        }
        statsNoAutoServiceTag.let { stats ->
            assertThat(stats.totalHits).isEqualTo(10)
            assertThat(stats.hits(ipsumBetaEchoService)).isEqualTo(5)
            assertThat(stats.hits(loremBetaEchoService)).isEqualTo(5)
        }
    }

    @Test
    fun `should reject request service-tag if it duplicates service-tag-preference`() {
        // given
        consul.server.operations.registerService(
            name = "echo",
            tags = listOf("lorem", "est"),
            extension = loremEchoService
        )
        waitForEndpointReady("echo", loremEchoService, autoServiceTagEnabledEnvoy)

        // when
        val notDuplicatedTagResponse = autoServiceTagEnabledEnvoy.egressOperations.callService(
            service = "echo",
            headers = mapOf("x-service-tag" to "est")
        )
        val duplicatedTagResponseLorem = autoServiceTagEnabledEnvoy.egressOperations.callService(
            service = "echo",
            headers = mapOf("x-service-tag" to "lorem")
        ).let { ResponseWithBody(it) }
        val duplicatedTagResponseIpsum = autoServiceTagEnabledEnvoy.egressOperations.callService(
            service = "echo",
            headers = mapOf("x-service-tag" to "ipsum")
        ).let { ResponseWithBody(it) }

        // then
        assertThat(notDuplicatedTagResponse).isOk().isFrom(loremEchoService)
        assertThat(duplicatedTagResponseLorem.response.code).isEqualTo(400)
        assertThat(duplicatedTagResponseLorem.body)
            .isEqualTo("Request service-tag 'lorem' duplicates auto service-tag preference. Remove service-tag parameter from the request")
        assertThat(duplicatedTagResponseIpsum.response.code).isEqualTo(400)
        assertThat(duplicatedTagResponseIpsum.body)
            .isEqualTo("Request service-tag 'ipsum' duplicates auto service-tag preference. Remove service-tag parameter from the request")
    }

    protected fun waitForEcConsulStateSynchronized(expectedInstancesIds: Collection<String>) {
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

    protected fun waitForEndpointReady(
        serviceName: String,
        serviceInstance: EchoServiceExtension,
        envoy: EnvoyExtension
    ) {
        envoy.waitForClusterEndpointHealthy(cluster = serviceName, endpointIp = serviceInstance.container().ipAddress())
    }

    protected fun waitForEndpointRemoved(
        serviceName: String,
        serviceInstance: EchoServiceExtension,
        envoy: EnvoyExtension
    ) {
        envoy.waitForClusterEndpointNotHealthy(
            cluster = serviceName,
            endpointIp = serviceInstance.container().ipAddress()
        )
    }

    private fun callStats() = CallStats(listOf(ipsumEchoService, loremEchoService, otherEchoService))

    protected fun callEchoTenTimes(
        envoy: EnvoyExtension,
        assertNoErrors: Boolean = true,
        tag: String? = null
    ): CallStats {
        val stats = callStats()
        envoy.egressOperations.callServiceRepeatedly(
            service = "echo",
            stats = stats,
            maxRepeat = 10,
            assertNoErrors = assertNoErrors,
            headers = tag?.let { mapOf("x-service-tag" to it) } ?: emptyMap(),
        )
        return stats
    }
}

class RoutingPolicyTest : RoutingPolicyTestBase(
    envoyControl = envoyControl,
    autoServiceTagEnabledEnvoy = autoServiceTagEnabledEnvoy,
    autoServiceTagDisabledEnvoy = autoServiceTagDisabledEnvoy,
) {
    companion object {
        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(
            consul, properties + mapOf(
                "envoy-control.envoy.snapshot.routing.service-tags.preference-routing.enable-for-all" to false,
                "envoy-control.envoy.snapshot.routing.service-tags.preference-routing.enable-for-services" to emptyList<String>()
            )
        )

        @JvmField
        @RegisterExtension
        val autoServiceTagEnabledEnvoy =
            EnvoyExtension(envoyControl, config = RandomConfigFile.copy(configOverride = autoServiceTagEnabledSettings))

        @JvmField
        @RegisterExtension
        val autoServiceTagDisabledEnvoy = EnvoyExtension(envoyControl)
    }

    @Test
    fun `should not filter service instances if autoServiceTag is false`() {
        // given
        waitForEcConsulStateSynchronized(
            listOf(
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
                consul.server.operations.registerService(
                    name = "echo",
                    extension = otherEchoService,
                    tags = emptyList()
                ),
            )
        )
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
    fun `should change routing when instance with prefered tag disappers`() {
        // given
        val ipsumId = consul.server.operations.registerService(
            name = "echo",
            extension = ipsumEchoService,
            tags = listOf("ipsum", "other")
        )
        val otherId = consul.server.operations.registerService(
            name = "echo",
            extension = otherEchoService,
            tags = listOf("other")
        )

        waitForEcConsulStateSynchronized(listOf(ipsumId, otherId))
        waitForEndpointReady("echo", otherEchoService, autoServiceTagDisabledEnvoy)
        waitForEndpointReady("echo", ipsumEchoService, autoServiceTagEnabledEnvoy)

        // when
        val statsNoAutoServiceTag = callEchoTenTimes(autoServiceTagDisabledEnvoy)
        val statsAutoServiceTag = callEchoTenTimes(autoServiceTagEnabledEnvoy)

        // then
        statsNoAutoServiceTag.let { stats ->
            assertThat(stats.totalHits).isEqualTo(10)
            assertThat(stats.hits(ipsumEchoService)).isEqualTo(5)
            assertThat(stats.hits(otherEchoService)).isEqualTo(5)
        }
        statsAutoServiceTag.let { stats ->
            assertThat(stats.totalHits).isEqualTo(10)
            assertThat(stats.hits(ipsumEchoService)).isEqualTo(10)
            assertThat(stats.hits(otherEchoService)).isEqualTo(0)
        }

        // when
        consul.server.operations.deregisterService(ipsumId)

        waitForEcConsulStateSynchronized(listOf(otherId))
        waitForEndpointRemoved("echo", ipsumEchoService, autoServiceTagDisabledEnvoy)
        waitForEndpointRemoved("echo", ipsumEchoService, autoServiceTagEnabledEnvoy)

        // and
        val statsNoAutoServiceTagAfterNoIpsum = callEchoTenTimes(autoServiceTagDisabledEnvoy)
        val statsAutoServiceTagAfterNoIpsum = callEchoTenTimes(autoServiceTagEnabledEnvoy, assertNoErrors = false)

        // then
        statsNoAutoServiceTagAfterNoIpsum.let { stats ->
            assertThat(stats.totalHits).isEqualTo(10)
            assertThat(stats.hits(otherEchoService)).isEqualTo(10)
        }
        statsAutoServiceTagAfterNoIpsum.let { stats ->
            assertThat(stats.totalHits).isEqualTo(10)
            assertThat(stats.failedHits).isEqualTo(10)
        }
    }

    @Test
    fun `should change routing when instance with prefered tag appears`() {
        // given
        val otherEchoId = consul.server.operations.registerService(
            name = "echo", extension = otherEchoService, tags = emptyList()
        )
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
            name = "echo", extension = loremEchoService, tags = listOf("lorem")
        )
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
            name = "echo", extension = ipsumEchoService, tags = listOf("noise", "ipsum")
        )
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
}

class RoutingPolicyWithServiceTagPreferenceEnabledTest : RoutingPolicyTestBase(
    envoyControl = envoyControl,
    autoServiceTagEnabledEnvoy = autoServiceTagEnabledEnvoy,
    autoServiceTagDisabledEnvoy = autoServiceTagDisabledEnvoy,
) {
    companion object {
        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(
            consul, properties + mapOf(
                "envoy-control.envoy.snapshot.routing.service-tags.preference-routing.enable-for-all" to true,
                "envoy-control.envoy.snapshot.routing.service-tags.preference-routing.default-preference-env" to "DEFAULT_SERVICE_TAG_PREFERENCE",
            )
        )

        @JvmField
        @RegisterExtension
        val autoServiceTagEnabledEnvoy =
            EnvoyExtension(
                envoyControl,
                config = RandomConfigFile.copy(configOverride = autoServiceTagEnabledSettings)
            ).also {
                it.container.withEnv("DEFAULT_SERVICE_TAG_PREFERENCE", "ipsum|lorem")
            }

        @JvmField
        @RegisterExtension
        val autoServiceTagDisabledEnvoy = EnvoyExtension(envoyControl).also {
            it.container.withEnv("DEFAULT_SERVICE_TAG_PREFERENCE", "ipsum|lorem")
        }
    }
}
