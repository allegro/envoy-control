package pl.allegro.tech.servicemesh.envoycontrol.routing

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.ClassOrderer.OrderAnnotation
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestClassOrder
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.CallStats
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.ServiceExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.UpstreamService
import pl.allegro.tech.servicemesh.envoycontrol.config.sharing.ContainerExtension
import pl.allegro.tech.servicemesh.envoycontrol.routing.ServiceTagPreferenceTest.Extensions.assertAllResponsesOkAndFrom
import pl.allegro.tech.servicemesh.envoycontrol.routing.ServiceTagPreferenceTest.Extensions.callServiceRepeatedly
import pl.allegro.tech.servicemesh.envoycontrol.routing.ServiceTagPreferenceTest.Extensions.deregisterInstance
import pl.allegro.tech.servicemesh.envoycontrol.routing.ServiceTagPreferenceTest.Extensions.registerInstance

@TestClassOrder(OrderAnnotation::class)
class ServiceTagPreferenceTest {

    companion object {

        val properties = mapOf(
            "envoy-control.envoy.snapshot.routing.service-tags.enabled" to true,
            "envoy-control.envoy.snapshot.routing.service-tags.auto-service-tag-enabled" to true, // TODO: testing with false?
            "envoy-control.envoy.snapshot.routing.service-tags.add-upstream-service-tags-header" to true,
            "envoy-control.envoy.snapshot.routing.service-tags.preference-routing.header" to "x-service-tag-preference",
            "envoy-control.envoy.snapshot.routing.service-tags.preference-routing.default-preference-env" to "DEFAULT_SERVICE_TAG_PREFERENCE",
            "envoy-control.envoy.snapshot.routing.service-tags.preference-routing.default-preference-fallback" to "global",
            "envoy-control.envoy.snapshot.routing.service-tags.preference-routing.enable-for-all" to true
        )

        @JvmField
        @RegisterExtension
        val consul = ConsulExtension(deregisterAfterEach = false)

        @JvmField
        @RegisterExtension
        val envoyControl: EnvoyControlExtension = EnvoyControlExtension(consul = consul, properties = properties)

        val envoyGlobal = EnvoyExtension(envoyControl = envoyControl)
        val envoyVte12 = EnvoyExtension(envoyControl = envoyControl).also {
            it.container.withEnv("DEFAULT_SERVICE_TAG_PREFERENCE", "vte12|global")
        }
        val envoyVte12Lvte1 = EnvoyExtension(envoyControl = envoyControl).also {
            it.container.withEnv("DEFAULT_SERVICE_TAG_PREFERENCE", "lvte1|vte12|global")
        }

        @JvmField
        @RegisterExtension
        val envoys = ContainerExtension.Parallel(envoyGlobal, envoyVte12, envoyVte12Lvte1)

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

    open class WhenAllServicesAreUpSetup {
        companion object {
            @JvmStatic
            @BeforeAll
            fun registerServices() {
                registerInstance(name = "echo", tags = listOf("global", "other-1"), echoGlobal)
                registerInstance(name = "echo", tags = listOf("vte12", "other-2"), echoVte12)
                registerInstance(name = "echo", tags = listOf("lvte1", "other-3"), echoVte12Lvte1)
                registerInstance(name = "echo", tags = listOf("vte33", "other-3"), echoVte33)

                listOf(envoyGlobal, envoyVte12, envoyVte12Lvte1).forEach { envoy ->
                    allServices.forEach { service ->
                        envoy.waitForClusterEndpointHealthy("echo", service.container().ipAddress())
                    }
                }
            }
        }
    }
    @Order(1)
    @Nested
    inner class WhenAllServicesAreUp : WhenAllServicesAreUpSetup() {

        @Test
        fun `requests are routed according to default service tag preference`() {
            envoyGlobal.callServiceRepeatedly(service = "echo")
                .assertAllResponsesOkAndFrom(instance = echoGlobal)
            envoyVte12.callServiceRepeatedly(service = "echo")
                .assertAllResponsesOkAndFrom(instance = echoVte12)
            envoyVte12Lvte1.callServiceRepeatedly(service = "echo")
                .assertAllResponsesOkAndFrom(instance = echoVte12Lvte1)
        }

        @Test
        fun `x-service-tag-preference from request overrides default one`() {

            envoyGlobal.callServiceRepeatedly(service = "echo", serviceTagPreference = "lvte1|vte12|global")
                .assertAllResponsesOkAndFrom(instance = echoVte12Lvte1)
            envoyVte12.callServiceRepeatedly(service = "echo", serviceTagPreference = "global")
                .assertAllResponsesOkAndFrom(instance = echoGlobal)
            envoyVte12.callServiceRepeatedly(service = "echo", serviceTagPreference = "lvte1|vte12|global")
                .assertAllResponsesOkAndFrom(instance = echoVte12Lvte1)
            envoyVte12Lvte1.callServiceRepeatedly(service = "echo", serviceTagPreference = "vte12|global")
                .assertAllResponsesOkAndFrom(instance = echoVte12)
        }

        @Test
        fun `x-service-tag overrides x-service-tag-preference`() {

            envoyGlobal.callServiceRepeatedly(service = "echo", serviceTag = "vte12")
                .assertAllResponsesOkAndFrom(instance = echoVte12)

            envoyVte12.callServiceRepeatedly(
                service = "echo",
                serviceTag = "global",
                serviceTagPreference = "lvte1|vte12|global"
            )
                .assertAllResponsesOkAndFrom(instance = echoGlobal)
        }
    }

    open class ThenLvteInstanceIsDownSetup {
        companion object {
            @JvmStatic
            @BeforeAll
            fun deregisterLvte() {
                deregisterInstance(echoVte12Lvte1)
            }
        }
    }
    @Order(2)
    @Nested
    inner class ThenLvteInstanceIsDown : ThenLvteInstanceIsDownSetup() {

        @Test
        fun `LVTE request falls back to base VTE instance`() {
            envoyGlobal.callServiceRepeatedly(service = "echo")
                .assertAllResponsesOkAndFrom(instance = echoGlobal)
            envoyVte12.callServiceRepeatedly(service = "echo")
                .assertAllResponsesOkAndFrom(instance = echoVte12)
            envoyVte12Lvte1.callServiceRepeatedly(service = "echo")
                .assertAllResponsesOkAndFrom(instance = echoVte12)
        }
    }

    /**
     * TODO:
     *   * add and pass default x-service-tag-preference header upstream
     *     * even if service-tag is used
     *   * pass request x-service-tag-preference upstream
     *     * even if service-tag is used
     *   * service whitelist test
     *   * disabled test
     *   * x-service-tag header based routing works without changes when preference routing is enabled
     *   * everything works with autoServiceTag enabled - especially request preference overriding default preference header sent to upstream
     *
     */

    private object Extensions {
        private const val REPEAT = 10
        fun EnvoyExtension.callServiceRepeatedly(
            service: String,
            serviceTagPreference: String? = null,
            serviceTag: String? = null
        ): CallStats =
            this.egressOperations.callServiceRepeatedly(
                service = service, stats = CallStats(allServices), minRepeat = REPEAT, maxRepeat = REPEAT,
                headers = buildMap {
                    if (serviceTagPreference != null) {
                        put("x-service-tag-preference", serviceTagPreference)
                    }
                    if (serviceTag != null) {
                        put("x-service-tag", serviceTag)
                    }
                }
            )

        fun CallStats.assertAllResponsesOkAndFrom(instance: UpstreamService) {
            assertThat(failedHits).isEqualTo(0)
            assertThat(hits(instance))
                .describedAs {
                    "hits: {" +
                        "global: ${hits(echoGlobal)}, " +
                        "vte12: ${hits(echoVte12)}, " +
                        "lvte1: ${hits(echoVte12Lvte1)}, " +
                        "vte33: ${hits(echoVte33)}}"
                }
                .isEqualTo(totalHits).isEqualTo(REPEAT)
        }

        fun registerInstance(name: String, tags: List<String>, extension: ServiceExtension<*>) {
            val id = consul.server.operations.registerService(name = name, tags = tags, extension = extension)
            consulRegisteredServicesIds[extension] = id
        }
        fun deregisterInstance(extension: ServiceExtension<*>) {
            val removed = consulRegisteredServicesIds.remove(extension)
            requireNotNull(removed) { "Extension was already unregistered!" }
            consul.server.operations.deregisterService(removed)
        }
        val consulRegisteredServicesIds: MutableMap<ServiceExtension<*>, String> = mutableMapOf()
    }
}
