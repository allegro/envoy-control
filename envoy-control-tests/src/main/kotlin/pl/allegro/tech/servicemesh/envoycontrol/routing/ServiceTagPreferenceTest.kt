package pl.allegro.tech.servicemesh.envoycontrol.routing

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.CallStats
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension

class ServiceTagPreferenceTest {

    companion object {

        val properties = mapOf(
            "envoy-control.envoy.snapshot.routing.service-tags.enabled" to true,
            "envoy-control.envoy.snapshot.routing.service-tags.auto-service-tag-enabled" to true,  // TODO: testing with false?
            "envoy-control.envoy.snapshot.routing.service-tags.add-upstream-service-tags-header" to true,
            "envoy-control.envoy.snapshot.routing.service-tags.preference-routing.header" to "x-service-tags-preference",
            "envoy-control.envoy.snapshot.routing.service-tags.preference-routing.default-preference-env" to "DEFAULT_SERVICE_TAG_PREFERENCE",
            "envoy-control.envoy.snapshot.routing.service-tags.preference-routing.default-preference-fallback" to "global",
            "envoy-control.envoy.snapshot.routing.service-tags.preference-routing.enable-for-all" to true
        )

        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        @JvmField
        @RegisterExtension
        val envoyControl: EnvoyControlExtension = EnvoyControlExtension(consul = consul, properties = properties)

        @JvmField
        @RegisterExtension
        val envoyGlobal = EnvoyExtension(envoyControl = envoyControl)

        @JvmField
        @RegisterExtension
        val envoyVte12 = EnvoyExtension(envoyControl = envoyControl).also {
            it.container.withEnv("DEFAULT_SERVICE_TAG_PREFERENCE", "vte12|global")
        }

        @JvmField
        @RegisterExtension
        val envoyVte12Lvte1 = EnvoyExtension(envoyControl = envoyControl).also {
            it.container.withEnv("DEFAULT_SERVICE_TAG_PREFERENCE", "lvte1|vte12|global")
        }

        @JvmField
        @RegisterExtension
        val echoGlobal = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val echoVte12 = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val echoVte12Lvte1 = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val echoVte33 = EchoServiceExtension()

        val allServices = listOf(echoGlobal, echoVte12, echoVte12Lvte1, echoVte33)
    }

    @Test
    fun `requests are routed according to default service tag preference`() {
        // given
        consul.server.operations.registerService(name = "echo", extension = echoGlobal, tags = listOf("global", "other-1"))
        consul.server.operations.registerService(name = "echo", extension = echoVte12, tags = listOf("vte12", "other-2"))
        consul.server.operations.registerService(name = "echo", extension = echoVte12Lvte1, tags = listOf("lvte1", "other-3"))
        consul.server.operations.registerService(name = "echo", extension = echoVte33, tags = listOf("vte33", "other-3"))

        listOf(envoyGlobal, envoyVte12, envoyVte12Lvte1).forEach { envoy ->
            allServices.forEach { service ->
                envoy.waitForClusterEndpointHealthy("echo", service.container().ipAddress())
            }
        }

        // expects
        envoyGlobal.callServiceRepeatedly(service = "echo")
            .assertAllResponsesOkAndFrom(instance = echoGlobal)

        envoyVte12.callServiceRepeatedly(service = "echo")
            .assertAllResponsesOkAndFrom(instance = echoVte12)

        envoyVte12Lvte1.callServiceRepeatedly(service = "echo")
            .assertAllResponsesOkAndFrom(instance = echoVte12Lvte1)
    }

    @Test
    fun `x-service-tag-preference from request overrides default one`() {
        // TODO: implement
        throw NotImplementedError()
    }

    @Test
    fun `x-service-tag overrides x-service-tag-preference`() {
        // TODO: implement
        throw NotImplementedError()
    }

    /**
     * TODO:
     *   * add and pass default x-service-tag-preference header upstream
     *   * pass request x-service-tag-preference upstream
     *   * service whitelist test
     *   * disabled test
     *   * x-service-tag header based routing works without changes when preference routing is enabled
     *   * everything works with autoServiceTag enabled - especially request preference overriding default preference header sent to upstream
     *
     */

    private val repeat = 10

    private fun EnvoyExtension.callServiceRepeatedly(service: String): CallStats =
        this.egressOperations.callServiceRepeatedly(
            service = service, stats = CallStats(allServices), minRepeat = repeat, maxRepeat = repeat
        )

    private fun CallStats.assertAllResponsesOkAndFrom(instance: EchoServiceExtension) {
        assertThat(failedHits).isEqualTo(0)
        assertThat(hits(instance))
            .describedAs {
                "hits: {global: ${hits(echoGlobal)}, vte12: ${hits(echoVte12)}, lvte1: ${hits(echoVte12Lvte1)}, vte33: ${hits(echoVte33)}}"
            }
            .isEqualTo(totalHits).isEqualTo(repeat)
    }
}
