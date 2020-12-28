package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.CallStats
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension

open class ServiceTagsTest {

    companion object {
        private val properties = mapOf(
            "envoy-control.envoy.snapshot.routing.service-tags.enabled" to true,
            "envoy-control.envoy.snapshot.routing.service-tags.metadata-key" to "tag",
            "envoy-control.envoy.snapshot.routing.service-tags.allowed-tags-combinations[0].service-name" to "service-1",
            "envoy-control.envoy.snapshot.routing.service-tags.allowed-tags-combinations[0].tags" to "version:.*,hardware:.*,role:.*",
            "envoy-control.envoy.snapshot.routing.service-tags.allowed-tags-combinations[1].service-name" to "service-2",
            "envoy-control.envoy.snapshot.routing.service-tags.allowed-tags-combinations[1].tags" to "version:.*,role:.*",
            "envoy-control.envoy.snapshot.routing.service-tags.allowed-tags-combinations[2].service-name" to "service-2",
            "envoy-control.envoy.snapshot.routing.service-tags.allowed-tags-combinations[2].tags" to "version:.*,hardware:.*",
            "envoy-control.envoy.snapshot.routing.service-tags.routing-excluded-tags" to "blacklist.*",
            "envoy-control.envoy.snapshot.load-balancing.canary.enabled" to false
        )

        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(consul, properties)

        @JvmField
        @RegisterExtension
        val regularService = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val loremService = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val loremIpsumService = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val genericService = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val envoy = EnvoyExtension(envoyControl, regularService)
    }

    protected fun registerServices() {
        consul.server.operations.registerService(name = "echo", extension = regularService, tags = emptyList())
        consul.server.operations.registerService(
            name = "echo",
            extension = loremService,
            tags = listOf("lorem", "blacklisted")
        )
        consul.server.operations.registerService(
            name = "echo",
            extension = loremIpsumService,
            tags = listOf("lorem", "ipsum")
        )
    }

    @Test
    open fun `should route requests to instance with tag ipsum`() {
        // given
        registerServices()
        envoy.waitForReadyServices("echo")

        // when
        val stats = callEchoServiceRepeatedly(repeat = 10, tag = "ipsum")

        // then
        assertThat(stats.totalHits).isEqualTo(10)
        assertThat(stats.hits(regularService)).isEqualTo(0)
        assertThat(stats.hits(loremService)).isEqualTo(0)
        assertThat(stats.hits(loremIpsumService)).isEqualTo(10)
    }

    @Test
    open fun `should route requests to instances with tag lorem`() {
        // given
        registerServices()
        envoy.waitForReadyServices("echo")

        // when
        val stats = callEchoServiceRepeatedly(repeat = 20, tag = "lorem")

        // then
        assertThat(stats.totalHits).isEqualTo(20)
        assertThat(stats.hits(regularService)).isEqualTo(0)
        assertThat(stats.hits(loremService)).isGreaterThan(2)
        assertThat(stats.hits(loremIpsumService)).isGreaterThan(2)
        assertThat(stats.hits(loremService) + stats.hits(loremIpsumService)).isEqualTo(20)
    }

    @Test
    open fun `should route requests to all instances`() {
        // given
        registerServices()
        envoy.waitForReadyServices("echo")

        // when
        val stats = callEchoServiceRepeatedly(repeat = 20)

        // then
        assertThat(stats.totalHits).isEqualTo(20)
        assertThat(stats.hits(regularService)).isGreaterThan(1)
        assertThat(stats.hits(loremService)).isGreaterThan(1)
        assertThat(stats.hits(loremIpsumService)).isGreaterThan(1)
        assertThat(
            stats.hits(regularService) + stats.hits(loremService) + stats.hits(
                loremIpsumService
            )
        ).isEqualTo(
            20
        )
    }

    @Test
    open fun `should return 503 if instance with requested tag is not found`() {
        // given
        registerServices()
        envoy.waitForReadyServices("echo")

        // when
        val stats = callEchoServiceRepeatedly(repeat = 10, tag = "dolom", assertNoErrors = false)

        // then
        assertThat(stats.totalHits).isEqualTo(10)
        assertThat(stats.failedHits).isEqualTo(10)
        assertThat(stats.hits(regularService)).isEqualTo(0)
        assertThat(stats.hits(loremService)).isEqualTo(0)
        assertThat(stats.hits(loremIpsumService)).isEqualTo(0)
    }

    @Test
    open fun `should return 503 if requested tag is blacklisted`() {
        // given
        registerServices()
        envoy.waitForReadyServices("echo")

        // when
        val stats = callEchoServiceRepeatedly(repeat = 10, tag = "blacklisted", assertNoErrors = false)

        // then
        assertThat(stats.totalHits).isEqualTo(10)
        assertThat(stats.failedHits).isEqualTo(10)
        assertThat(stats.hits(regularService)).isEqualTo(0)
        assertThat(stats.hits(loremService)).isEqualTo(0)
        assertThat(stats.hits(loremIpsumService)).isEqualTo(0)
    }

    @Test
    open fun `should route request with three tags if combination is valid`() {
        // given
        val matching = loremService
        val notMatching = loremIpsumService

        consul.server.operations.registerService(
            name = "service-1", extension = matching,
            tags = listOf("version:v1.5", "hardware:c32", "role:master")
        )
        consul.server.operations.registerService(
            name = "service-1", extension = notMatching,
            tags = listOf("version:v1.5", "hardware:c64", "role:master")
        )

        envoy.waitForReadyServices("service-1")

        // when
        val stats = callServiceRepeatedly(
            service = "service-1", repeat = 10, tag = "hardware:c32,role:master,version:v1.5"
        )

        // then
        assertThat(stats.totalHits).isEqualTo(10)
        assertThat(stats.hits(matching)).isEqualTo(10)
        assertThat(stats.hits(notMatching)).isEqualTo(0)
    }

    @Test
    open fun `should not route request with multiple tags if service is not whitelisted`() {
        // given
        val matching = loremService

        consul.server.operations.registerService(
            name = "service-3", extension = matching,
            tags = listOf("version:v1.5", "hardware:c32", "role:master")
        )

        envoy.waitForReadyServices("service-3")

        // when
        val threeTagsStats = callServiceRepeatedly(
            service = "service-3", repeat = 10, tag = "hardware:c32,role:master,version:v1.5", assertNoErrors = false
        )
        val twoTagsStats = callServiceRepeatedly(
            service = "service-3", repeat = 10, tag = "hardware:c32,role:master", assertNoErrors = false
        )
        val oneTagStats = callServiceRepeatedly(
            service = "service-3", repeat = 10, tag = "role:master"
        )

        // then
        assertThat(threeTagsStats.totalHits).isEqualTo(10)
        assertThat(threeTagsStats.failedHits).isEqualTo(10)
        assertThat(threeTagsStats.hits(matching)).isEqualTo(0)

        assertThat(twoTagsStats.totalHits).isEqualTo(10)
        assertThat(twoTagsStats.failedHits).isEqualTo(10)
        assertThat(twoTagsStats.hits(matching)).isEqualTo(0)

        assertThat(oneTagStats.totalHits).isEqualTo(10)
        assertThat(oneTagStats.failedHits).isEqualTo(0)
        assertThat(oneTagStats.hits(matching)).isEqualTo(10)
    }

    @Test
    open fun `should not route request with three tags if combination is not allowed`() {
        // given
        val service1Matching = loremService
        val service2Matching = loremIpsumService

        consul.server.operations.registerService(
            name = "service-1", extension = service1Matching,
            tags = listOf("version:v1.5", "hardware:c32", "ram:512")
        )
        consul.server.operations.registerService(
            name = "service-2", extension = service2Matching,
            tags = listOf("version:v1.5", "hardware:c32", "role:master")
        )

        envoy.waitForReadyServices("service-1", "service-2")

        // when
        val service1Stats = callServiceRepeatedly(
            service = "service-1", repeat = 10, tag = "hardware:c32,ram:512,version:v1.5", assertNoErrors = false
        )
        val service2Stats = callServiceRepeatedly(
            service = "service-2", repeat = 10, tag = "hardware:c32,role:master,version:v1.5", assertNoErrors = false
        )

        // then
        assertThat(service1Stats.totalHits).isEqualTo(10)
        assertThat(service1Stats.failedHits).isEqualTo(10)
        assertThat(service1Stats.hits(service1Matching)).isEqualTo(0)

        assertThat(service2Stats.totalHits).isEqualTo(10)
        assertThat(service2Stats.failedHits).isEqualTo(10)
        assertThat(service2Stats.hits(service1Matching)).isEqualTo(0)
    }

    @Test
    open fun `should route request with two tags if combination is valid`() {
        // given
        val service1Matching = loremService
        val service1NotMatching = regularService
        val service2Master = loremIpsumService
        val service2Secondary = genericService

        consul.server.operations.registerService(
            name = "service-1", extension = service1Matching,
            tags = listOf("version:v2.0", "hardware:c32", "role:master")
        )
        consul.server.operations.registerService(
            name = "service-1", extension = service1NotMatching,
            tags = listOf("version:v1.5", "hardware:c32", "role:master")
        )
        consul.server.operations.registerService(
            name = "service-2", extension = service2Master,
            tags = listOf("version:v1.5", "hardware:c32", "role:master")
        )
        consul.server.operations.registerService(
            name = "service-2", extension = service2Secondary,
            tags = listOf("version:v2.0", "hardware:c32", "role:secondary")
        )

        envoy.waitForReadyServices("service-1", "service-2")

        // when
        val service1Stats = callServiceRepeatedly(
            service = "service-1", repeat = 10, tag = "hardware:c32,version:v2.0"
        )
        val service2MasterStats = callServiceRepeatedly(
            service = "service-2", repeat = 10, tag = "hardware:c32,version:v1.5"
        )
        val service2SecondaryStats = callServiceRepeatedly(
            service = "service-2", repeat = 10, tag = "role:secondary,version:v2.0"
        )

        // then
        assertThat(service1Stats.totalHits).isEqualTo(10)
        assertThat(service1Stats.hits(service1Matching)).isEqualTo(10)

        assertThat(service2MasterStats.totalHits).isEqualTo(10)
        assertThat(service2MasterStats.hits(service2Master)).isEqualTo(10)

        assertThat(service2SecondaryStats.totalHits).isEqualTo(10)
        assertThat(service2SecondaryStats.hits(service2Secondary)).isEqualTo(10)
    }

    @Test
    open fun `should not route request with two tags if combination is not allowed`() {
        // given
        val matching = loremService

        consul.server.operations.registerService(
            name = "service-2", extension = matching,
            tags = listOf("version:v1.5", "hardware:c32", "role:master")
        )

        envoy.waitForReadyServices("service-2")

        // when
        val stats = callServiceRepeatedly(
            service = "service-2", repeat = 10, tag = "hardware:c32,role:master", assertNoErrors = false
        )

        // then
        assertThat(stats.totalHits).isEqualTo(10)
        assertThat(stats.failedHits).isEqualTo(10)
        assertThat(stats.hits(matching)).isEqualTo(0)
    }

    protected fun callEchoServiceRepeatedly(
        repeat: Int,
        tag: String? = null,
        assertNoErrors: Boolean = true
    ): CallStats {
        return callServiceRepeatedly(
            service = "echo",
            repeat = repeat,
            tag = tag,
            assertNoErrors = assertNoErrors
        )
    }

    protected open fun callStats() = CallStats(listOf(regularService, loremService, loremIpsumService, genericService))

    protected open fun callServiceRepeatedly(
        service: String,
        repeat: Int,
        tag: String? = null,
        assertNoErrors: Boolean = true
    ): CallStats {
        val stats = callStats()
        envoy.egressOperations.callServiceRepeatedly(
            service = service,
            stats = stats,
            minRepeat = repeat,
            maxRepeat = repeat,
            headers = tag?.let { mapOf("x-service-tag" to it) } ?: emptyMap(),
            assertNoErrors = assertNoErrors
        )
        return stats
    }
}
