package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.routes

import com.google.protobuf.util.Durations
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.groups.ClientWithSelector
import pl.allegro.tech.servicemesh.envoycontrol.groups.HealthCheck
import pl.allegro.tech.servicemesh.envoycontrol.groups.Incoming
import pl.allegro.tech.servicemesh.envoycontrol.groups.Incoming.TimeoutPolicy
import pl.allegro.tech.servicemesh.envoycontrol.groups.IncomingRateLimitEndpoint
import pl.allegro.tech.servicemesh.envoycontrol.groups.PathMatchingType
import pl.allegro.tech.servicemesh.envoycontrol.groups.ProxySettings
import pl.allegro.tech.servicemesh.envoycontrol.groups.ServicesGroup
import pl.allegro.tech.servicemesh.envoycontrol.groups.adminPostAuthorizedRoute
import pl.allegro.tech.servicemesh.envoycontrol.groups.adminPostRoute
import pl.allegro.tech.servicemesh.envoycontrol.groups.adminRedirectRoute
import pl.allegro.tech.servicemesh.envoycontrol.groups.adminRoute
import pl.allegro.tech.servicemesh.envoycontrol.groups.configDumpAuthorizedRoute
import pl.allegro.tech.servicemesh.envoycontrol.groups.configDumpRoute
import pl.allegro.tech.servicemesh.envoycontrol.groups.googleRegexMatcher
import pl.allegro.tech.servicemesh.envoycontrol.groups.googleRegexMethodMatcher
import pl.allegro.tech.servicemesh.envoycontrol.groups.googleRegexPathMatcher
import pl.allegro.tech.servicemesh.envoycontrol.groups.hasNoRetryPolicy
import pl.allegro.tech.servicemesh.envoycontrol.groups.hasOneDomain
import pl.allegro.tech.servicemesh.envoycontrol.groups.hasOnlyHeaderValueMatchActionWithMatchersInOrder
import pl.allegro.tech.servicemesh.envoycontrol.groups.hasOnlyRoutesInOrder
import pl.allegro.tech.servicemesh.envoycontrol.groups.hasRateLimitsInOrder
import pl.allegro.tech.servicemesh.envoycontrol.groups.hasRequestHeadersToRemove
import pl.allegro.tech.servicemesh.envoycontrol.groups.hasResponseHeaderToAdd
import pl.allegro.tech.servicemesh.envoycontrol.groups.hasSingleVirtualHostThat
import pl.allegro.tech.servicemesh.envoycontrol.groups.hasStatusVirtualClusters
import pl.allegro.tech.servicemesh.envoycontrol.groups.ingressRoute
import pl.allegro.tech.servicemesh.envoycontrol.groups.matchingOnAnyMethod
import pl.allegro.tech.servicemesh.envoycontrol.groups.matchingOnMethod
import pl.allegro.tech.servicemesh.envoycontrol.groups.matchingRetryPolicy
import pl.allegro.tech.servicemesh.envoycontrol.groups.pathMatcher
import pl.allegro.tech.servicemesh.envoycontrol.groups.prefixPathMatcher
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.EndpointMatch
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.LocalRetryPoliciesProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.LocalRetryPolicyProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SecuredRoute
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotProperties
import java.time.Duration

internal class EnvoyIngressRoutesFactoryTest {

    private val retryPolicyProps = LocalRetryPoliciesProperties().apply {
        default = LocalRetryPolicyProperties().apply {
            enabled = true
            retryOn = mutableSetOf("connection-failure")
            numRetries = 3
        }
        perHttpMethod = mutableMapOf(
            "GET" to LocalRetryPolicyProperties().apply {
                enabled = true
                retryOn = mutableSetOf("reset", "connection-failure")
                numRetries = 1
                perTryTimeout = Duration.ofSeconds(1)
                hostSelectionRetryMaxAttempts = 3
            },
            "HEAD" to LocalRetryPolicyProperties().apply {
                enabled = true
                retryOn = mutableSetOf("connection-failure")
                numRetries = 6
            },
            "POST" to LocalRetryPolicyProperties().apply {
                enabled = false
                retryOn = mutableSetOf("connection-failure")
                numRetries = 6
            }
        )
    }

    private val adminRoutes = arrayOf(
        configDumpAuthorizedRoute(),
        configDumpRoute(),
        adminPostAuthorizedRoute(),
        adminPostRoute(),
        adminRoute(),
        adminRedirectRoute()
    )

    @Test
    fun `should create route config with health check and response timeout defined`() {
        // given
        val routesFactory = EnvoyIngressRoutesFactory(SnapshotProperties().apply {
            routes.status.enabled = true
            routes.status.endpoints = mutableListOf(EndpointMatch())
            routes.status.createVirtualCluster = true
            localService.retryPolicy = retryPolicyProps
            routes.admin.publicAccessEnabled = true
            routes.admin.token = "test_token"
            routes.admin.securedPaths.add(SecuredRoute().apply {
                pathPrefix = "/config_dump"
                method = "GET"
            })
        })
        val responseTimeout = Durations.fromSeconds(777)
        val idleTimeout = Durations.fromSeconds(61)
        val connectionIdleTimeout = Durations.fromSeconds(120)
        val proxySettingsOneEndpoint = ProxySettings(
            incoming = Incoming(
                healthCheck = HealthCheck(
                    path = "",
                    clusterName = "health_check_cluster"
                ),
                permissionsEnabled = true,
                timeoutPolicy = TimeoutPolicy(idleTimeout, responseTimeout, connectionIdleTimeout),
                rateLimitEndpoints = listOf(
                    IncomingRateLimitEndpoint("/hello", PathMatchingType.PATH_PREFIX, setOf("GET", "POST"),
                        setOf(ClientWithSelector.create("client-1", "selector")), "100/s"),
                    IncomingRateLimitEndpoint("/banned", PathMatchingType.PATH, setOf("GET"),
                        setOf(ClientWithSelector.create("*")), "0/m"),
                    IncomingRateLimitEndpoint("/a/.*", PathMatchingType.PATH_REGEX, emptySet(),
                        setOf(ClientWithSelector.create("client-2")), "0/m")
                )
            )
        )
        val group = ServicesGroup(
            "service_1",
            "service_1",
            proxySettingsOneEndpoint
        )

        // when
        val routeConfig = routesFactory.createSecuredIngressRouteConfig(
            "service_1",
            proxySettingsOneEndpoint,
            group
        )

        // then
        routeConfig
            .hasSingleVirtualHostThat {
                hasStatusVirtualClusters()
                hasOneDomain("*")
                hasOnlyRoutesInOrder(
                    *adminRoutes,
                    {
                        ingressRoute()
                        matchingOnMethod("GET")
                        matchingRetryPolicy(retryPolicyProps.perHttpMethod["GET"]!!)
                        hasRateLimitsInOrder(
                            {
                                hasOnlyHeaderValueMatchActionWithMatchersInOrder(
                                    { prefixPathMatcher("/hello") },
                                    { googleRegexMethodMatcher("^GET$|^POST$") },
                                    { googleRegexMatcher("x-service-name", "^client-1:selector$") }
                                )
                            },
                            {
                                hasOnlyHeaderValueMatchActionWithMatchersInOrder(
                                    { pathMatcher("/banned") },
                                    { googleRegexMethodMatcher("^GET$") }
                                )
                            },
                            {
                                hasOnlyHeaderValueMatchActionWithMatchersInOrder(
                                    { googleRegexPathMatcher("/a/.*") },
                                    { googleRegexMatcher("x-service-name", "^client-2$") }
                                )
                            }
                        )
                    },
                    {
                        ingressRoute()
                        matchingOnMethod("HEAD")
                        matchingRetryPolicy(retryPolicyProps.perHttpMethod["HEAD"]!!)
                    },
                    {
                        ingressRoute()
                        matchingOnAnyMethod()
                        hasNoRetryPolicy()
                    }
                )
                matchingRetryPolicy(retryPolicyProps.default)
            }
    }

    @Test
    fun `should create route config with headers to remove and add`() {
        // given
        val routesFactory = EnvoyIngressRoutesFactory(SnapshotProperties().apply {
            ingress.headersToRemove = mutableListOf("x-via-vip", "x-special-case-header")
            ingress.addServiceNameHeaderToResponse = true
            ingress.addRequestedAuthorityHeaderToResponse = true
        })
        val proxySettingsOneEndpoint = ProxySettings(
            incoming = Incoming(
                healthCheck = HealthCheck(
                    path = "",
                    clusterName = "health_check_cluster"
                ),
                permissionsEnabled = true
            )
        )
        val group = ServicesGroup(
            "service_1",
            "service_1",
            proxySettingsOneEndpoint
        )

        // when
        val routeConfig = routesFactory.createSecuredIngressRouteConfig(
            "service_1",
            proxySettingsOneEndpoint,
            group
        )

        // then
        routeConfig
            .hasRequestHeadersToRemove(listOf("x-via-vip", "x-special-case-header"))
            .hasResponseHeaderToAdd("x-service-name", "service_1")
            .hasResponseHeaderToAdd("x-requested-authority", "%REQ(:authority)%")
    }
}
