package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters

import com.google.protobuf.Any
import io.envoyproxy.envoy.extensions.filters.http.header_to_metadata.v3.Config
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.GlobalSnapshot
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotProperties

class EnvoyDefaultFilters(
    private val snapshotProperties: SnapshotProperties
) {
    private val rbacFilterFactory = RBACFilterFactory(
        snapshotProperties.incomingPermissions,
        snapshotProperties.routes.status,
        jwtProperties = snapshotProperties.jwt
    )
    private val luaFilterFactory = LuaFilterFactory(
        snapshotProperties.incomingPermissions
    )
    private val jwtFilterFactory = JwtFilterFactory(
        snapshotProperties.jwt
    )
    private val rateLimitFilterFactory = RateLimitFilterFactory(
        snapshotProperties.rateLimit
    )

    private val defaultServiceTagFilterRules = ServiceTagFilter.serviceTagFilterRules(
        snapshotProperties.routing.serviceTags.header,
        snapshotProperties.routing.serviceTags.metadataKey
    )
    private val defaultHeaderToMetadataConfig = headerToMetadataConfig(defaultServiceTagFilterRules)
    private val headerToMetadataHttpFilter = headerToMetadataHttpFilter(defaultHeaderToMetadataConfig)
    private val defaultHeaderToMetadataFilter = { _: Group, _: GlobalSnapshot -> headerToMetadataHttpFilter }
    private val envoyRouterHttpFilter = envoyRouterHttpFilter()
    /**
     * Default filters should not be private, user should have an option to pick any filter.
     * Remember: order matters.
     */
    val defaultEnvoyRouterHttpFilter = { _: Group, _: GlobalSnapshot -> envoyRouterHttpFilter }
    val defaultRateLimitLuaFilter = { group: Group, _: GlobalSnapshot ->
        rateLimitFilterFactory.createLuaLimitOverrideFilter(group)
    }
    val defaultRateLimitFilter = { group: Group, _: GlobalSnapshot ->
        rateLimitFilterFactory.createGlobalRateLimitFilter(group)
    }
    val defaultRbacFilter = { group: Group, snapshot: GlobalSnapshot ->
        rbacFilterFactory.createHttpFilter(group, snapshot)
    }
    val defaultRbacLoggingFilter = { group: Group, _: GlobalSnapshot ->
        luaFilterFactory.ingressRbacLoggingFilter(group)
    }

    val defaultClientNameHeaderFilter = { _: Group, _: GlobalSnapshot ->
        luaFilterFactory.ingressClientNameHeaderFilter()
    }

    val defaultJwtHttpFilter = { group: Group, _: GlobalSnapshot -> jwtFilterFactory.createJwtFilter(group) }

    val defaultAuthorizationHeaderFilter = { _: Group, _: GlobalSnapshot ->
        authorizationHeaderToMetadataFilter()
    }
    val defaultEgressFilters = listOf(defaultHeaderToMetadataFilter, defaultEnvoyRouterHttpFilter)

    /**
     * Order matters:
     * * defaultClientNameHeaderFilter has to be before defaultRbacLoggingFilter, because the latter consumes results of
     *   the former
     * * defaultRbacLoggingFilter has to be before defaultRbacFilter, otherwise unauthorised requests will not be
     *   logged, because the RBAC filter will stop filter chain execution and subsequent filters will not process
     *   the request
     * * defaultEnvoyRouterHttpFilter - router filter should be always the last filter.
     */
    val defaultIngressFilters = listOf(
        defaultClientNameHeaderFilter,
        defaultAuthorizationHeaderFilter,
        defaultJwtHttpFilter,
        defaultRbacLoggingFilter,
        defaultRbacFilter,
        defaultRateLimitLuaFilter,
        defaultRateLimitFilter,
        defaultEnvoyRouterHttpFilter
    )
    val defaultIngressMetadata = { group: Group -> luaFilterFactory.ingressScriptsMetadata(group) }

    private fun headerToMetadataConfig(
        rules: List<Config.Rule>,
        key: String = snapshotProperties.loadBalancing.canary.metadataKey
    ): Config.Builder {
        val headerToMetadataConfig = Config.newBuilder()
            .addRequestRules(
                Config.Rule.newBuilder()
                    .setHeader("x-canary")
                    .setRemove(false)
                    .setOnHeaderPresent(
                        Config.KeyValuePair.newBuilder()
                            .setKey(key)
                            .setMetadataNamespace("envoy.lb")
                            .setType(Config.ValueType.STRING)
                            .build()
                    )
                    .build()
            )

        rules.forEach {
            headerToMetadataConfig.addRequestRules(it)
        }

        return headerToMetadataConfig
    }

    private fun envoyRouterHttpFilter(): HttpFilter = HttpFilter
        .newBuilder()
        .setName("envoy.filters.http.router")
        .build()

    private fun headerToMetadataHttpFilter(headerToMetadataConfig: Config.Builder): HttpFilter {
        return HttpFilter.newBuilder()
            .setName("envoy.filters.http.header_to_metadata")
            .setTypedConfig(
                Any.pack(
                    headerToMetadataConfig.build()
                )
            )
            .build()
    }

    private fun authorizationHeaderToMetadataFilter(): HttpFilter =
        HttpFilter.newBuilder().setName("envoy.filters.http.header_to_metadata").setTypedConfig(
            Any.pack(
                Config.newBuilder()
                    .addRequestRules(
                        Config.Rule.newBuilder().setHeader("authorization")
                            .setOnHeaderMissing(
                                Config.KeyValuePair.newBuilder().setKey("jwt-status").setValue("missing")
                            )
                            .setOnHeaderPresent(
                                Config.KeyValuePair.newBuilder().setKey("jwt-status").setValue("present")
                            )
                            .setRemove(false)
                            .build()

                    )
                    .build()
            )
        ).build()
}
