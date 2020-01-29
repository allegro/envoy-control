package pl.allegro.tech.servicemesh.envoycontrol.snapshot

import com.google.protobuf.util.Durations
import io.envoyproxy.envoy.api.v2.route.Route
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.groups.HealthCheck
import pl.allegro.tech.servicemesh.envoycontrol.groups.Incoming
import pl.allegro.tech.servicemesh.envoycontrol.groups.Incoming.TimeoutPolicy
import pl.allegro.tech.servicemesh.envoycontrol.groups.IncomingEndpoint
import pl.allegro.tech.servicemesh.envoycontrol.groups.ProxySettings
import pl.allegro.tech.servicemesh.envoycontrol.groups.Role
import pl.allegro.tech.servicemesh.envoycontrol.groups.adminPostAuthorizedRoute
import pl.allegro.tech.servicemesh.envoycontrol.groups.adminPostRoute
import pl.allegro.tech.servicemesh.envoycontrol.groups.adminRedirectRoute
import pl.allegro.tech.servicemesh.envoycontrol.groups.adminRoute
import pl.allegro.tech.servicemesh.envoycontrol.groups.allOpenIngressRoute
import pl.allegro.tech.servicemesh.envoycontrol.groups.configDumpAuthorizedRoute
import pl.allegro.tech.servicemesh.envoycontrol.groups.configDumpRoute
import pl.allegro.tech.servicemesh.envoycontrol.groups.fallbackIngressRoute
import pl.allegro.tech.servicemesh.envoycontrol.groups.hasNoRetryPolicy
import pl.allegro.tech.servicemesh.envoycontrol.groups.hasOneDomain
import pl.allegro.tech.servicemesh.envoycontrol.groups.hasOnlyRoutesInOrder
import pl.allegro.tech.servicemesh.envoycontrol.groups.hasSingleVirtualHostThat
import pl.allegro.tech.servicemesh.envoycontrol.groups.hasStatusVirtualClusters
import pl.allegro.tech.servicemesh.envoycontrol.groups.matchingOnAnyMethod
import pl.allegro.tech.servicemesh.envoycontrol.groups.matchingOnMethod
import pl.allegro.tech.servicemesh.envoycontrol.groups.matchingRetryPolicy
import pl.allegro.tech.servicemesh.envoycontrol.groups.statusRoute
import java.time.Duration

internal class EnvoyIngressRoutesFactoryTest {

    private val retryPolicyProps = RetryPoliciesProperties().apply {
        default = RetryPolicyProperties().apply {
            enabled = true
            retryOn = mutableSetOf("connection-failure")
            numRetries = 3
        }
        perHttpMethod = mutableMapOf(
            "GET" to RetryPolicyProperties().apply {
                enabled = true
                retryOn = mutableSetOf("reset", "connection-failure")
                numRetries = 1
                perTryTimeout = Duration.ofSeconds(1)
                hostSelectionRetryMaxAttempts = 3
            },
            "HEAD" to RetryPolicyProperties().apply {
                enabled = true
                retryOn = mutableSetOf("connection-failure")
                numRetries = 6
            },
            "POST" to RetryPolicyProperties().apply {
                enabled = false
                retryOn = mutableSetOf("connection-failure")
                numRetries = 6
            }
        )
    }
    private val routesFactory = EnvoyIngressRoutesFactory(SnapshotProperties().apply {
        routes.status.enabled = true
        routes.status.createVirtualCluster = true
        localService.retryPolicy = retryPolicyProps
        routes.admin.publicAccessEnabled = true
        routes.admin.token = "test_token"
        routes.admin.securedPaths.add(SecuredRoute().apply {
            pathPrefix = "/config_dump"
            method = "GET"
        })
    })
    private val adminRoutes = arrayOf(
        configDumpAuthorizedRoute(),
        configDumpRoute(),
        adminPostAuthorizedRoute(),
        adminPostRoute(),
        adminRoute(),
        adminRedirectRoute()
    )

    private val getRoute: Route.() -> Unit = {
        allOpenIngressRoute()
        matchingOnMethod("GET")
        matchingRetryPolicy(retryPolicyProps.perHttpMethod["GET"]!!)
    }
    private val headRoute: Route.() -> Unit = {
        allOpenIngressRoute()
        matchingOnMethod("HEAD")
        matchingRetryPolicy(retryPolicyProps.perHttpMethod["HEAD"]!!)
    }
    private val otherRoute: Route.() -> Unit = {
        allOpenIngressRoute()
        matchingOnAnyMethod()
        hasNoRetryPolicy()
    }

    private val ingressRoutes = arrayOf(
            getRoute, headRoute, otherRoute
    )

    @Test
    fun `should create legacy ingress route config`() {
        // given
        val emptyProxySettings = ProxySettings()

        // when
        val routeConfig = routesFactory.createSecuredIngressRouteConfig(emptyProxySettings)

        // then
        routeConfig
            .hasSingleVirtualHostThat {
                hasStatusVirtualClusters()
                hasOneDomain("*")
                hasOnlyRoutesInOrder(
                    *adminRoutes,
                    *ingressRoutes
                )
                matchingRetryPolicy(retryPolicyProps.default)
            }
    }

    @Test
    fun `should create route config with no endpoints allowed`() {
        // given
        val proxySettingsNoEndpoints = ProxySettings(
            incoming = Incoming(endpoints = listOf(), permissionsEnabled = true)
        )

        // when
        val routeConfig = routesFactory.createSecuredIngressRouteConfig(proxySettingsNoEndpoints)

        // then
        routeConfig
            .hasSingleVirtualHostThat {
                hasStatusVirtualClusters()
                hasOneDomain("*")
                hasOnlyRoutesInOrder(
                    *adminRoutes,
                    statusRoute(),
                    *ingressRoutes,
                    fallbackIngressRoute()
                )
            }
    }

    @Test
    fun `should create route config with two simple endpoints and response timeout defined`() {
        // given
        val responseTimeout = Durations.fromSeconds(777)
        val idleTimeout = Durations.fromSeconds(61)
        val proxySettingsOneEndpoint = ProxySettings(
            incoming = Incoming(
                healthCheck = HealthCheck(
                    path = "",
                    clusterName = "health_check_cluster"
                ),
                endpoints = listOf(
                    IncomingEndpoint(
                        path = "/endpoint",
                        clients = setOf("client1")
                    ),
                    IncomingEndpoint(
                        path = "/products",
                        clients = setOf("client2"),
                        methods = setOf("POST")
                    )
                ),
                permissionsEnabled = true,
                timeoutPolicy = TimeoutPolicy(idleTimeout, responseTimeout)
            )
        )

        // when
        val routeConfig = routesFactory.createSecuredIngressRouteConfig(proxySettingsOneEndpoint)

        // then
        routeConfig
            .hasSingleVirtualHostThat {
                hasStatusVirtualClusters()
                hasOneDomain("*")
                hasOnlyRoutesInOrder(
                    *adminRoutes,
                    statusRoute(idleTimeout, responseTimeout),
                    *ingressRoutes,
                    fallbackIngressRoute()
                )
                matchingRetryPolicy(retryPolicyProps.default)
            }
    }

    @Test
    fun `should create multiple routes for multiple methods and clients`() {
        // given
        val proxySettings = ProxySettings(
            incoming = Incoming(
                healthCheck = HealthCheck(
                    path = "/status/custom",
                    clusterName = "local_service_health_check"
                ),
                endpoints = listOf(
                    IncomingEndpoint(
                        path = "/endpoint",
                        clients = setOf("client1", "group1"),
                        methods = setOf("GET", "POST")
                    )
                ),
                permissionsEnabled = true,
                roles = listOf(
                    Role(name = "group1", clients = setOf("clientB", "other-client")),
                    Role(name = "group2", clients = setOf("clientC"))
                )
            )
        )

        // when
        val routeConfig = routesFactory.createSecuredIngressRouteConfig(proxySettings)

        // then
        routeConfig
            .hasSingleVirtualHostThat {
                hasStatusVirtualClusters()
                hasOneDomain("*")
                hasOnlyRoutesInOrder(
                    *adminRoutes,
                    statusRoute(clusterName = "local_service_health_check", healthCheckPath = "/status/custom"),
                    statusRoute(),
                    *ingressRoutes,
                    fallbackIngressRoute()
                )
                matchingRetryPolicy(retryPolicyProps.default)
            }
    }
}
