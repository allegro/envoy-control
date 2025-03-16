package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters

import com.google.protobuf.Any
import io.envoyproxy.envoy.extensions.filters.http.header_to_metadata.v3.Config
import io.envoyproxy.envoy.extensions.filters.http.router.v3.Router
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.GlobalSnapshot
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.HttpFilterFactory

class EnvoyDefaultFilters(
    private val snapshotProperties: SnapshotProperties,
    private val customLuaMetadata: LuaMetadataProperty.StructPropertyLua
) {
    private val rbacFilterFactory = RBACFilterFactory(
        snapshotProperties.incomingPermissions,
        snapshotProperties.routes.status,
        jwtProperties = snapshotProperties.jwt
    )
    private val luaFilterFactory = LuaFilterFactory(snapshotProperties)
    private val jwtFilterFactory = JwtFilterFactory(
        snapshotProperties.jwt
    )
    private val rateLimitFilterFactory = RateLimitFilterFactory(
        snapshotProperties.rateLimit
    )
    private val serviceTagFilterFactory = ServiceTagFilterFactory(
        properties = snapshotProperties.routing.serviceTags
    )

    private val compressionFilterFactory = CompressionFilterFactory(snapshotProperties)

    private val defaultServiceTagHeaderToMetadataFilterRules = serviceTagFilterFactory.headerToMetadataFilterRules()
    private val defaultHeaderToMetadataConfig = headerToMetadataConfig(defaultServiceTagHeaderToMetadataFilterRules)
    private val headerToMetadataHttpFilter = headerToMetadataHttpFilter(defaultHeaderToMetadataConfig)
    private val defaultHeaderToMetadataFilter = { _: Group, _: GlobalSnapshot -> headerToMetadataHttpFilter }
    private val defaultServiceTagFilters = serviceTagFilterFactory.egressFilters()
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

    val defaultGzipCompressionFilter = { group: Group, _: GlobalSnapshot ->
        compressionFilterFactory.gzipCompressionFilter(group)
    }

    val defaultBrotliCompressionFilter = { group: Group, _: GlobalSnapshot ->
        compressionFilterFactory.brotliCompressionFilter(group)
    }

    val defaultEgressFilters: List<HttpFilterFactory> = listOf(
        defaultHeaderToMetadataFilter,
        *defaultServiceTagFilters,
        defaultGzipCompressionFilter,
        defaultBrotliCompressionFilter,
        defaultEnvoyRouterHttpFilter,
    )

    val defaultCurrentZoneHeaderFilter = { _: Group, _: GlobalSnapshot ->
        luaFilterFactory.ingressCurrentZoneHeaderFilter()
    }

    /**
     * Order matters:
     * * defaultClientNameHeaderFilter has to be before defaultRbacLoggingFilter, because the latter consumes results of
     *   the former
     * * defaultRbacLoggingFilter has to be before defaultRbacFilter, otherwise unauthorised requests will not be
     *   logged, because the RBAC filter will stop filter chain execution and subsequent filters will not process
     *   the request
     * * defaultEnvoyRouterHttpFilter - router filter should be always the last filter.
     *
     *  Instead of it, use:
     *  @see ingressFilters()
     */
    @Deprecated("It becomes deprecated, because user has no option to add new filters.")
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

    /**
     * Creates a list of http filters with provided user filters. It respects order for crucial default filters.
     * This function is a recommended way to create list of ingress filters.
     */
    fun ingressFilters(vararg filters: (Group, GlobalSnapshot) -> HttpFilter?):
        List<(Group, GlobalSnapshot) -> HttpFilter?> {
        val preFilters = listOf(
            defaultClientNameHeaderFilter,
            defaultAuthorizationHeaderFilter,
            defaultJwtHttpFilter,
            defaultCurrentZoneHeaderFilter
        )
        val postFilters = listOf(
            defaultRbacLoggingFilter,
            defaultRbacFilter,
            defaultRateLimitLuaFilter,
            defaultRateLimitFilter,
            defaultEnvoyRouterHttpFilter
        )
        return preFilters + filters.toList() + postFilters
    }

    val defaultIngressMetadata = { group: Group, currentZone: String ->
        luaFilterFactory.ingressScriptsMetadata(group, customLuaMetadata, currentZone)
    }

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
        .setTypedConfig(
            Any.pack(
                Router.newBuilder()
                    .build()
            )
        )
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
