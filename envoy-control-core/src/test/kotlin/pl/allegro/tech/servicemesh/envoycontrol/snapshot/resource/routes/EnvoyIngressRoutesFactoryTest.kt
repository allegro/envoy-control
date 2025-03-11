package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.routes

import com.google.protobuf.util.Durations
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.groups.ClientWithSelector
import pl.allegro.tech.servicemesh.envoycontrol.groups.CommunicationMode
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
import pl.allegro.tech.servicemesh.envoycontrol.groups.ingressServiceRoute
import pl.allegro.tech.servicemesh.envoycontrol.groups.ingressStatusRoute
import pl.allegro.tech.servicemesh.envoycontrol.groups.matchingOnAnyMethod
import pl.allegro.tech.servicemesh.envoycontrol.groups.matchingOnMethod
import pl.allegro.tech.servicemesh.envoycontrol.groups.matchingOnPrefix
import pl.allegro.tech.servicemesh.envoycontrol.groups.matchingRetryPolicy
import pl.allegro.tech.servicemesh.envoycontrol.groups.pathMatcher
import pl.allegro.tech.servicemesh.envoycontrol.groups.prefixPathMatcher
import pl.allegro.tech.servicemesh.envoycontrol.groups.publicAccess
import pl.allegro.tech.servicemesh.envoycontrol.groups.toCluster
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.CustomRuteProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.EndpointMatch
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.FeatureWhiteList
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.LocalRetryPoliciesProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.LocalRetryPolicyProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SecuredRoute
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.StringMatcher
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.StringMatcherType
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
    private val currentZone = "dc1"

    @Test
    @Suppress("LongMethod")
    fun `should create route config with health check and response timeout defined`() {
        // given
        val routesFactory = EnvoyIngressRoutesFactory(SnapshotProperties().apply {
            routes.status.apply {
                enabled = true
                endpoints = mutableListOf(EndpointMatch().apply {
                    path = "/status/"
                    matchingType = PathMatchingType.PATH_PREFIX
                })
                createVirtualCluster = true
                separatedRouteWhiteList = FeatureWhiteList(listOf("service_1"))
            }
            routes.status.createVirtualCluster = true
            localService.retryPolicy = retryPolicyProps
            routes.admin.publicAccessEnabled = true
            routes.admin.token = "test_token"
            routes.admin.securedPaths.add(SecuredRoute().apply {
                pathPrefix = "/config_dump"
                method = "GET"
            })
            routes.customs = listOf(CustomRuteProperties().apply {
                enabled = true
                cluster = "wrapper"
                path = StringMatcher().apply {
                    type = StringMatcherType.PREFIX
                    value = "/status/wrapper/"
                }
            })
        }, currentZone = currentZone)
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
                    IncomingRateLimitEndpoint(
                        "/hello", PathMatchingType.PATH_PREFIX, setOf("GET", "POST"),
                        setOf(ClientWithSelector.create("client-1", "selector")), "100/s"
                    ),
                    IncomingRateLimitEndpoint(
                        "/banned", PathMatchingType.PATH, setOf("GET"),
                        setOf(ClientWithSelector.create("*")), "0/m"
                    ),
                    IncomingRateLimitEndpoint(
                        "/a/.*", PathMatchingType.PATH_REGEX, emptySet(),
                        setOf(ClientWithSelector.create("client-2")), "0/m"
                    )
                )
            )
        )
        val group = ServicesGroup(
            communicationMode = CommunicationMode.XDS,
            serviceName = "service_1",
            discoveryServiceName = "service_1",
            proxySettings = proxySettingsOneEndpoint
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
                    {
                        matchingOnPrefix("/status/wrapper/")
                            .toCluster("wrapper")
                            .publicAccess()
                    },
                    *adminRoutes,
                    {
                        ingressStatusRoute()
                        matchingOnMethod("GET")
                        matchingRetryPolicy(retryPolicyProps.perHttpMethod["GET"]!!)
                    },
                    {
                        ingressStatusRoute()
                        matchingOnMethod("HEAD")
                        matchingRetryPolicy(retryPolicyProps.perHttpMethod["HEAD"]!!)
                    },
                    {
                        ingressStatusRoute()
                        matchingOnAnyMethod()
                        hasNoRetryPolicy()
                    },
                    {
                        ingressServiceRoute()
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
                        ingressServiceRoute()
                        matchingOnMethod("HEAD")
                        matchingRetryPolicy(retryPolicyProps.perHttpMethod["HEAD"]!!)
                    },
                    {
                        ingressServiceRoute()
                        matchingOnAnyMethod()
                        hasNoRetryPolicy()
                    }
                )
                matchingRetryPolicy(retryPolicyProps.default)
            }
    }

    @Test
    @Suppress("LongMethod")
    fun `should not create routes for status endpoints if the service is not whitelisted`() {
        // given
        val routesFactory = EnvoyIngressRoutesFactory(SnapshotProperties().apply {
            routes.status.apply {
                enabled = true
                endpoints = mutableListOf(EndpointMatch().apply {
                    path = "/status/"
                    matchingType = PathMatchingType.PATH_PREFIX
                })
                createVirtualCluster = true
                separatedRouteWhiteList = FeatureWhiteList(listOf("service_123"))
            }
            routes.status.createVirtualCluster = true
            localService.retryPolicy = retryPolicyProps
            routes.admin.publicAccessEnabled = true
            routes.admin.token = "test_token"
            routes.admin.securedPaths.add(SecuredRoute().apply {
                pathPrefix = "/config_dump"
                method = "GET"
            })
        }, currentZone = currentZone)
        val proxySettings = ProxySettings()
        val group = ServicesGroup(
            communicationMode = CommunicationMode.XDS,
            serviceName = "service_1",
            discoveryServiceName = "service_1",
            proxySettings = proxySettings
        )

        // when
        val routeConfig = routesFactory.createSecuredIngressRouteConfig(
            "service_1",
            proxySettings,
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
                        ingressServiceRoute()
                        matchingOnMethod("GET")
                        matchingRetryPolicy(retryPolicyProps.perHttpMethod["GET"]!!)
                    },
                    {
                        ingressServiceRoute()
                        matchingOnMethod("HEAD")
                        matchingRetryPolicy(retryPolicyProps.perHttpMethod["HEAD"]!!)
                    },
                    {
                        ingressServiceRoute()
                        matchingOnAnyMethod()
                        hasNoRetryPolicy()
                    }
                )
                matchingRetryPolicy(retryPolicyProps.default)
            }
    }

    @Test
    @Suppress("LongMethod")
    fun `should create routes for status endpoints when whitelist contains wildcard`() {
        // given
        val routesFactory = EnvoyIngressRoutesFactory(SnapshotProperties().apply {
            routes.status.apply {
                enabled = true
                endpoints = mutableListOf(EndpointMatch().apply {
                    path = "/status/"
                    matchingType = PathMatchingType.PATH_PREFIX
                })
                createVirtualCluster = true
                separatedRouteWhiteList = FeatureWhiteList(listOf("service_123", "*"))
            }
            routes.status.createVirtualCluster = true
            localService.retryPolicy = retryPolicyProps
            routes.admin.publicAccessEnabled = true
            routes.admin.token = "test_token"
            routes.admin.securedPaths.add(SecuredRoute().apply {
                pathPrefix = "/config_dump"
                method = "GET"
            })
        }, currentZone = currentZone)
        val proxySettings = ProxySettings()
        val group = ServicesGroup(
            communicationMode = CommunicationMode.XDS,
            serviceName = "service_1",
            discoveryServiceName = "service_1",
            proxySettings = proxySettings
        )

        // when
        val routeConfig = routesFactory.createSecuredIngressRouteConfig(
            "service_1",
            proxySettings,
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
                        ingressStatusRoute()
                        matchingOnMethod("GET")
                        matchingRetryPolicy(retryPolicyProps.perHttpMethod["GET"]!!)
                    },
                    {
                        ingressStatusRoute()
                        matchingOnMethod("HEAD")
                        matchingRetryPolicy(retryPolicyProps.perHttpMethod["HEAD"]!!)
                    },
                    {
                        ingressStatusRoute()
                        matchingOnAnyMethod()
                        hasNoRetryPolicy()
                    },
                    {
                        ingressServiceRoute()
                        matchingOnMethod("GET")
                        matchingRetryPolicy(retryPolicyProps.perHttpMethod["GET"]!!)
                    },
                    {
                        ingressServiceRoute()
                        matchingOnMethod("HEAD")
                        matchingRetryPolicy(retryPolicyProps.perHttpMethod["HEAD"]!!)
                    },
                    {
                        ingressServiceRoute()
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
        }, currentZone = currentZone)
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
            communicationMode = CommunicationMode.XDS,
            serviceName = "service_1",
            discoveryServiceName = "service_1",
            proxySettings = proxySettingsOneEndpoint
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
