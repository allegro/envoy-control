package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.routes

import com.google.protobuf.util.Durations
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.groups.DependencySettings
import pl.allegro.tech.servicemesh.envoycontrol.groups.Outgoing
import pl.allegro.tech.servicemesh.envoycontrol.groups.RetryPolicy
import pl.allegro.tech.servicemesh.envoycontrol.groups.hasCustomIdleTimeout
import pl.allegro.tech.servicemesh.envoycontrol.groups.hasCustomRequestTimeout
import pl.allegro.tech.servicemesh.envoycontrol.groups.hasHostRewriteHeader
import pl.allegro.tech.servicemesh.envoycontrol.groups.hasNoRequestHeaderToAdd
import pl.allegro.tech.servicemesh.envoycontrol.groups.hasNoRetryPolicy
import pl.allegro.tech.servicemesh.envoycontrol.groups.hasRequestHeaderToAdd
import pl.allegro.tech.servicemesh.envoycontrol.groups.hasRequestHeadersToRemove
import pl.allegro.tech.servicemesh.envoycontrol.groups.hasResponseHeaderToAdd
import pl.allegro.tech.servicemesh.envoycontrol.groups.hasRetryPolicy
import pl.allegro.tech.servicemesh.envoycontrol.groups.hasVirtualHostThat
import pl.allegro.tech.servicemesh.envoycontrol.groups.hasVirtualHostsInOrder
import pl.allegro.tech.servicemesh.envoycontrol.groups.hostRewriteHeaderIsEmpty
import pl.allegro.tech.servicemesh.envoycontrol.groups.matchingOnAnyMethod
import pl.allegro.tech.servicemesh.envoycontrol.groups.matchingOnMethod
import pl.allegro.tech.servicemesh.envoycontrol.groups.matchingOnPrefix
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.RouteSpecification
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.StandardRouteSpecification

internal class EnvoyEgressRoutesFactoryTest {

    val clusters = listOf(
        StandardRouteSpecification(
            clusterName = "srv1",
            routeDomains = listOf("srv1"),
            settings = DependencySettings(
                handleInternalRedirect = true,
                timeoutPolicy = Outgoing.TimeoutPolicy(
                    idleTimeout = Durations.fromSeconds(10L),
                    requestTimeout = Durations.fromSeconds(10L)
                ),
                rewriteHostHeader = true,
                retryPolicy = RetryPolicy()
            )
        )
    )

    @Test
    fun `should add client identity header if incoming permissions are enabled`() {
        // given
        val routesFactory = EnvoyEgressRoutesFactory(SnapshotProperties().apply {
            incomingPermissions.enabled = true
        })

        // when
        val routeConfig = routesFactory.createEgressRouteConfig("client1", clusters, false)

        // then
        routeConfig
            .hasRequestHeaderToAdd("x-service-name", "client1")

        routeConfig
            .virtualHostsList[0]
            .routesList[0]
            .route
            .hasCustomIdleTimeout(Durations.fromSeconds(10L))
            .hasCustomRequestTimeout(Durations.fromSeconds(10L))
    }

    @Test
    fun `should not add client identity header if incoming permissions are disabled`() {
        // given
        val routesFactory = EnvoyEgressRoutesFactory(SnapshotProperties().apply {
            incomingPermissions.enabled = false
        })

        // when
        val routeConfig = routesFactory.createEgressRouteConfig("client1", clusters, false)

        // then
        routeConfig
            .hasNoRequestHeaderToAdd("x-service-name")

        routeConfig
            .virtualHostsList[0]
            .routesList[0]
            .route
            .hasCustomIdleTimeout(Durations.fromSeconds(10L))
            .hasCustomRequestTimeout(Durations.fromSeconds(10L))
    }

    @Test
    fun `should add upstream remote address header if addUpstreamAddress is enabled`() {
        // given
        val routesFactory = EnvoyEgressRoutesFactory(SnapshotProperties())

        // when
        val routeConfig = routesFactory.createEgressRouteConfig("client1", clusters, true)

        // then
        routeConfig
            .hasResponseHeaderToAdd("x-envoy-upstream-remote-address", "%UPSTREAM_REMOTE_ADDRESS%")
    }

    @Test
    fun `should not add upstream remote address header if addUpstreamAddress is disabled`() {
        // given
        val routesFactory = EnvoyEgressRoutesFactory(SnapshotProperties())

        // when
        val routeConfig = routesFactory.createEgressRouteConfig("client1", clusters, false)

        // then
        routeConfig
            .hasNoRequestHeaderToAdd("x-envoy-upstream-remote-address")
    }

    @Test
    fun `should not add auto rewrite host header when feature is disabled in configuration`() {
        // given
        val routesFactory = EnvoyEgressRoutesFactory(SnapshotProperties().apply {
            egress.hostHeaderRewriting.enabled = false
        })

        // when
        val routeConfig = routesFactory.createEgressRouteConfig("client1", clusters, false)

        // then
        routeConfig
            .virtualHostsList[0]
            .routesList[0]
            .route
            .hostRewriteHeaderIsEmpty()
    }

    @Test
    fun `should add auto rewrite host header when feature is disabled in configuration`() {
        // given
        val snapshotProperties = SnapshotProperties().apply {
            egress.hostHeaderRewriting.enabled = true
            egress.hostHeaderRewriting.customHostHeader = "test_header"
        }
        val routesFactory = EnvoyEgressRoutesFactory(snapshotProperties)

        // when
        val routeConfig = routesFactory.createEgressRouteConfig("client1", clusters, false)

        // then
        routeConfig
            .virtualHostsList[0]
            .routesList[0]
            .route
            .hasHostRewriteHeader(snapshotProperties.egress.hostHeaderRewriting.customHostHeader)
    }

    @Test
    fun `should create route config with headers to remove`() {
        // given
        val routesFactory = EnvoyEgressRoutesFactory(SnapshotProperties().apply {
            egress.headersToRemove = mutableListOf("x-special-case-header", "x-custom")
        })

        // when
        val routeConfig = routesFactory.createEgressRouteConfig("client1", clusters, false)

        // then
        routeConfig.hasRequestHeadersToRemove(listOf("x-special-case-header", "x-custom"))
    }

    @Test
    fun `should create route config for domains`() {
        // given
        val routesFactory = EnvoyEgressRoutesFactory(SnapshotProperties().apply {
            egress.headersToRemove = mutableListOf("x-special-case-header", "x-custom")
        })
        val routesSpecifications = listOf(
            StandardRouteSpecification("example_pl_1553", listOf("example.pl:1553"), DependencySettings()),
            StandardRouteSpecification("example_com_1553", listOf("example.com:1553"), DependencySettings())
        )

        // when
        val routeConfig = routesFactory.createEgressDomainRoutes(routesSpecifications, routeName = "1553")

        // then

        routeConfig.hasVirtualHostsInOrder(
            {
                it.domainsCount == 1 && it.name == "example_pl_1553" && it.domainsList[0] == "example.pl:1553"
            },
            {
                it.domainsCount == 1 && it.name == "example_com_1553" && it.domainsList[0] == "example.com:1553"
            },
            {
                it.domainsCount == 1 && it.name == "original-destination-route" && it.domainsList[0] == "envoy-original-destination"
            },
            {
                it.domainsCount == 1 && it.name == "wildcard-route" && it.domainsList[0] == "*"
            }
        )
        routeConfig.hasRequestHeadersToRemove(listOf("x-special-case-header", "x-custom"))
    }

    @Test
    fun `should create route config and apply retry police only to specified methods`() {
        // given
        val routesFactory = EnvoyEgressRoutesFactory(SnapshotProperties())
        val retryPolicy = RetryPolicy(methods = setOf("GET", "POST"))
        val routesSpecifications = listOf(
            StandardRouteSpecification("example", listOf("example.pl:1553"), DependencySettings(retryPolicy = retryPolicy)),
        )

        val routeConfig = routesFactory.createEgressRouteConfig(
            "test",
            routesSpecifications,
            routeName = "retry",
            addUpstreamAddressHeader = false
        )

        routeConfig.hasVirtualHostThat("example") {
            assertEquals(2, routesCount)
            val retryRoute = getRoutes(0)
            val defaultRoute = getRoutes(1)

            retryRoute.hasRetryPolicy()
            retryRoute.matchingOnMethod("GET|POST", true)
            retryRoute.matchingOnPrefix("/")

            defaultRoute.hasNoRetryPolicy()
            defaultRoute.matchingOnAnyMethod()
            defaultRoute.matchingOnPrefix("/")
        }
    }

    @Test
    fun `should create route config and apply retry policy without specified methods to default prefix and all method`() {
        // given
        val routesFactory = EnvoyEgressRoutesFactory(SnapshotProperties())
        val retryPolicy = RetryPolicy(numberRetries = 3)
        val routesSpecifications = listOf(
            StandardRouteSpecification("example", listOf("example.pl:1553"), DependencySettings(retryPolicy = retryPolicy)),
        )

        val routeConfig = routesFactory.createEgressRouteConfig(
            "test",
            routesSpecifications,
            routeName = "retry",
            addUpstreamAddressHeader = false
        )

        routeConfig.hasVirtualHostThat("example") {
            assertEquals(1, routesCount)
            val defaultRoute = getRoutes(0)

            defaultRoute.hasRetryPolicy()
            defaultRoute.matchingOnAnyMethod()
            defaultRoute.matchingOnPrefix("/")
        }
    }

    @Test
    fun `should create route config and not apply retry policy if it is not specified`() {
        // given
        val routesFactory = EnvoyEgressRoutesFactory(SnapshotProperties())
        val routesSpecifications = listOf(
            StandardRouteSpecification("example", listOf("example.pl:1553"), DependencySettings()),
        )

        val routeConfig = routesFactory.createEgressRouteConfig(
            "test",
            routesSpecifications,
            routeName = "retry",
            addUpstreamAddressHeader = false
        )

        routeConfig.hasVirtualHostThat("example") {
            assertEquals(1, routesCount)
            val defaultRoute = getRoutes(0)

            defaultRoute.hasNoRetryPolicy()
            defaultRoute.matchingOnAnyMethod()
            defaultRoute.matchingOnPrefix("/")
        }
    }
}
