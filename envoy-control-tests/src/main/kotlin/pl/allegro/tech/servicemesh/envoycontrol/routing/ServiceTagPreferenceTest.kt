package pl.allegro.tech.servicemesh.envoycontrol.routing

import okhttp3.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.ClassOrderer.OrderAnnotation
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestClassOrder
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.RandomConfigFile
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.CallStats
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.HttpsEchoExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.ServiceExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.UpstreamService
import pl.allegro.tech.servicemesh.envoycontrol.config.service.asHttpsEchoResponse
import pl.allegro.tech.servicemesh.envoycontrol.config.sharing.ContainerExtension
import pl.allegro.tech.servicemesh.envoycontrol.routing.ServiceTagPreferenceTest.Extensions.deregisterInstance
import pl.allegro.tech.servicemesh.envoycontrol.routing.ServiceTagPreferenceTest.Extensions.registerInstance

@TestClassOrder(OrderAnnotation::class)
class ServiceTagPreferenceTest : ServiceTagPreferenceTestBase(allServices = allServices) {

    companion object {
        @JvmField
        @RegisterExtension
        val envoyControl: EnvoyControlExtension = EnvoyControlExtension(consul = consul, properties = properties)

        val envoyGlobal = EnvoyExtension(envoyControl = envoyControl)
        val envoyGlobalDisabled = EnvoyExtension(
            envoyControl = envoyControl,
            config = RandomConfigFile.copy(serviceName = "disabled-service")
        )
        val envoyVte12 = EnvoyExtension(envoyControl = envoyControl).also {
            it.container.withEnv("DEFAULT_SERVICE_TAG_PREFERENCE", "vte12|global")
        }
        val envoyVte12Lvte1 = EnvoyExtension(envoyControl = envoyControl).also {
            it.container.withEnv("DEFAULT_SERVICE_TAG_PREFERENCE", "lvte1|vte12|global")
        }
        val echoGlobal = HttpsEchoExtension()
        val echoVte12 = HttpsEchoExtension()
        val echoVte12Lvte1 = HttpsEchoExtension()
        val echoVte33 = HttpsEchoExtension()

        @JvmField
        @RegisterExtension
        val containers = ContainerExtension.Parallel(
            envoyGlobal, envoyVte12, envoyVte12Lvte1, envoyGlobalDisabled,
            echoGlobal, echoVte12, echoVte12Lvte1, echoVte33
        )

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
                registerInstance(name = "echo", tags = listOf("vte33", "other-3", "cz"), echoVte33)

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
            envoyVte12.callServiceRepeatedly(service = "echo", serviceTag = "cz")
                .assertAllResponsesOkAndFrom(instance = echoVte33)

            envoyVte12.callServiceRepeatedly(
                service = "echo",
                serviceTag = "global",
                serviceTagPreference = "lvte1|vte12|global"
            )
                .assertAllResponsesOkAndFrom(instance = echoGlobal)
        }

        @Test
        fun `x-service-tag-preference is passed upstream`() {

            envoyGlobal.callService(service = "echo").asHttpsEchoResponse().let {
                assertThat(it.requestHeaders).containsEntry("x-service-tag-preference", "global")
            }
            envoyVte12Lvte1.callService(service = "echo").asHttpsEchoResponse().let {
                assertThat(it.requestHeaders).containsEntry("x-service-tag-preference", "lvte1|vte12|global")
            }
            envoyGlobal.callService(service = "echo", serviceTagPreference = "vte66|global").asHttpsEchoResponse().let {
                assertThat(it.requestHeaders).containsEntry("x-service-tag-preference", "vte66|global")
            }

            envoyVte12.callService(service = "echo", serviceTag = "cz").asHttpsEchoResponse().let {
                assertThat(it.requestHeaders).containsEntry("x-service-tag-preference", "vte12|global")
            }

            envoyVte12.callService(service = "echo", serviceTag = "cz", serviceTagPreference = "lvte1|vte12|global")
                .asHttpsEchoResponse().let {
                    assertThat(it.requestHeaders).containsEntry("x-service-tag-preference", "lvte1|vte12|global")
                }
            // TODO[PROM-5262]: more!
        }

        @Test
        fun `preference routing is disabled for selected service`() {
            envoyGlobal.callServiceRepeatedly(service = "echo")
                .assertAllResponsesOkAndFrom(echoGlobal)
            envoyGlobalDisabled.callServiceRepeatedly(service = "echo")
                .assertResponsesFromRandomInstances()
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
     *   + [DONE] add and pass default x-service-tag-preference header upstream
     *     + [DONE] even if service-tag is used
     *   + [DONE] pass request x-service-tag-preference upstream
     *     + [DONE] even if service-tag is used
     *   * test with 503 (no instances for given preference)
     *     * + verify service-tag-preference response field [AEC]
     *   + [DONE] blacklist (+ add varnish)
     *    + [DONE] test
     *   * [DONE] service whitelist test
     *   * [ABANDONED] disabled test. Abandoned because other tests test that.
     *   * x-service-tag header based routing works without changes when preference routing is enabled
     *   * [DONE] everything works with autoServiceTag enabled - especially request preference overriding default preference header sent to upstream
     *   * x-envoy-upstream-service-tags : move or copy the test here?
     *   * change docs preferences table
     *   * test with MIN envoy version
     */

    override fun CallStats.report() =
        "hits: {" +
            "global: ${hits(echoGlobal)}, " +
            "vte12: ${hits(echoVte12)}, " +
            "lvte1: ${hits(echoVte12Lvte1)}, " +
            "vte33: ${hits(echoVte33)}}"

    private object Extensions {
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

class ServiceTagPreferenceEnableForServicesTest :
    ServiceTagPreferenceTestBase(allServices = listOf(echoGlobal, echoVte3)) {

    companion object {
        @JvmField
        @RegisterExtension
        val envoyControl: EnvoyControlExtension = EnvoyControlExtension(
            consul = consul, properties = properties + mapOf(
                "envoy-control.envoy.snapshot.routing.service-tags.preference-routing.enable-for-all" to false,
                "envoy-control.envoy.snapshot.routing.service-tags.preference-routing.enable-for-services" to listOf("enabled-service")
            )
        )

        val echoGlobal = HttpsEchoExtension() // TODO: change to EchoExtension
        val echoVte3 = HttpsEchoExtension()
        val envoyGlobal = EnvoyExtension(envoyControl = envoyControl)
        val envoyGlobalEnabled = EnvoyExtension(
            envoyControl = envoyControl, config = RandomConfigFile.copy(serviceName = "enabled-service")
        )
        val allServices = listOf(echoGlobal, echoVte3)

        @JvmField
        @RegisterExtension
        val containers = ContainerExtension.Parallel(echoGlobal, echoVte3, envoyGlobal, envoyGlobalEnabled)

        @JvmStatic
        @BeforeAll
        fun registerServices() {
            consul.server.operations.registerService(name = "echo", tags = listOf("global"), extension = echoGlobal)
            consul.server.operations.registerService(name = "echo", tags = listOf("vte3"), extension = echoVte3)

            listOf(envoyGlobal, envoyGlobalEnabled).forEach { envoy ->
                allServices.forEach { service ->
                    envoy.waitForClusterEndpointHealthy("echo", service.container().ipAddress())
                }
            }
        }
    }

    @Test
    fun `preference routing enabled only for selected service`() {

        envoyGlobal.callServiceRepeatedly(service = "echo")
            .assertResponsesFromRandomInstances()
        envoyGlobalEnabled.callServiceRepeatedly(service = "echo")
            .assertAllResponsesOkAndFrom(echoGlobal)
    }

    override fun CallStats.report() =
        "hits: {" +
            "global: ${hits(echoGlobal)}, " +
            "vte3: ${hits(echoVte3)}}"
}

abstract class ServiceTagPreferenceTestBase(val allServices: List<UpstreamService>) {
    companion object {
        val properties = mapOf(
            "envoy-control.envoy.snapshot.routing.service-tags.enabled" to true,
            "envoy-control.envoy.snapshot.routing.service-tags.auto-service-tag-enabled" to true,
            "envoy-control.envoy.snapshot.routing.service-tags.add-upstream-service-tags-header" to true,
            "envoy-control.envoy.snapshot.routing.service-tags.preference-routing.header" to "x-service-tag-preference",
            "envoy-control.envoy.snapshot.routing.service-tags.preference-routing.default-preference-env" to "DEFAULT_SERVICE_TAG_PREFERENCE",
            "envoy-control.envoy.snapshot.routing.service-tags.preference-routing.default-preference-fallback" to "global",
            "envoy-control.envoy.snapshot.routing.service-tags.preference-routing.enable-for-all" to true,
            "envoy-control.envoy.snapshot.routing.service-tags.preference-routing.disable-for-services" to listOf("disabled-service")
        )

        @JvmField
        @RegisterExtension
        val consul = ConsulExtension(deregisterAfterEach = false)
    }

    private val REPEAT = 10
    fun EnvoyExtension.callServiceRepeatedly(
        service: String,
        serviceTagPreference: String? = null,
        serviceTag: String? = null
    ): CallStats =
        this.egressOperations.callServiceRepeatedly(
            service = service, stats = CallStats(allServices), minRepeat = REPEAT, maxRepeat = REPEAT,
            headers = headers(serviceTagPreference = serviceTagPreference, serviceTag = serviceTag)
        )

    private fun headers(serviceTagPreference: String?, serviceTag: String?) = buildMap {
        if (serviceTagPreference != null) {
            put("x-service-tag-preference", serviceTagPreference)
        }
        if (serviceTag != null) {
            put("x-service-tag", serviceTag)
        }
    }

    fun EnvoyExtension.callService(
        service: String,
        serviceTagPreference: String? = null,
        serviceTag: String? = null
    ): Response =
        this.egressOperations.callService(
            service = service,
            headers = headers(serviceTagPreference = serviceTagPreference, serviceTag = serviceTag),
        )

    fun CallStats.assertAllResponsesOkAndFrom(instance: UpstreamService) {
        assertThat(failedHits).isEqualTo(0)
        assertThat(hits(instance))
            .describedAs { report() }
            .isEqualTo(totalHits).isEqualTo(REPEAT)
    }

    fun CallStats.assertResponsesFromRandomInstances() {
        assertThat(failedHits).isEqualTo(0)
        assertThat(allServices).hasSizeGreaterThan(1)
        assertThat(allServices).describedAs { report() }.allSatisfy {
            assertThat(hits(it)).isGreaterThan(0)
        }
    }

    abstract fun CallStats.report(): String
}
