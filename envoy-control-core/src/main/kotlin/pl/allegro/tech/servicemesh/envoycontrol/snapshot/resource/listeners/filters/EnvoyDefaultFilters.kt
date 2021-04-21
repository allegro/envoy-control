package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters

import com.google.protobuf.Any
import com.google.protobuf.Duration
import io.envoyproxy.envoy.config.core.v3.HttpUri
import io.envoyproxy.envoy.config.core.v3.Metadata
import io.envoyproxy.envoy.config.route.v3.RouteMatch
import io.envoyproxy.envoy.extensions.filters.http.header_to_metadata.v3.Config
import io.envoyproxy.envoy.extensions.filters.http.jwt_authn.v3.JwtAuthentication
import io.envoyproxy.envoy.extensions.filters.http.jwt_authn.v3.JwtProvider
import io.envoyproxy.envoy.extensions.filters.http.jwt_authn.v3.JwtRequirement
import io.envoyproxy.envoy.extensions.filters.http.jwt_authn.v3.RemoteJwks
import io.envoyproxy.envoy.extensions.filters.http.jwt_authn.v3.RequirementRule
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.GlobalSnapshot
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotProperties

class EnvoyDefaultFilters(
    private val snapshotProperties: SnapshotProperties
) {
    private val rbacFilterFactory = RBACFilterFactory(
        snapshotProperties.incomingPermissions,
        snapshotProperties.routes.status
    )
    private val luaFilterFactory = LuaFilterFactory(
        snapshotProperties.incomingPermissions
    )
    private val jwtFilterFactory = JwtFilterFactory(
        snapshotProperties.jwt
    )

    private val defaultServiceTagFilterRules = ServiceTagFilter.serviceTagFilterRules(
        snapshotProperties.routing.serviceTags.header,
        snapshotProperties.routing.serviceTags.metadataKey
    )
    private val defaultHeaderToMetadataConfig = headerToMetadataConfig(defaultServiceTagFilterRules)
    private val headerToMetadataHttpFilter = headerToMetadataHttpFilter(defaultHeaderToMetadataConfig)
    private val defaultHeaderToMetadataFilter = { _: Group, _: GlobalSnapshot -> headerToMetadataHttpFilter }
    private val envoyRouterHttpFilter = envoyRouterHttpFilter()
    private val defaultEnvoyRouterHttpFilter = { _: Group, _: GlobalSnapshot -> envoyRouterHttpFilter }
    private val defaultRbacFilter = { group: Group, snapshot: GlobalSnapshot ->
        rbacFilterFactory.createHttpFilter(group, snapshot)
    }
    private val defaultRbacLoggingFilter = { group: Group, _: GlobalSnapshot ->
        luaFilterFactory.ingressRbacLoggingFilter(group)
    }

    private val defaultClientNameHeaderFilter = { group: Group, _: GlobalSnapshot ->
        luaFilterFactory.ingressClientNameHeaderFilter()
    }
    //todo: change for filter factory
    private val jwtHttpFilter = createJwtFilter()
    private val defaultJwtHttpFilter = { _: Group, _: GlobalSnapshot -> jwtHttpFilter }

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
        defaultJwtHttpFilter,
        defaultRbacLoggingFilter,
        defaultRbacFilter,
        defaultEnvoyRouterHttpFilter
    )
    val defaultIngressMetadata: Metadata = luaFilterFactory.ingressScriptsMetadata()

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
    //todo: remove when setting filters from properties is working
    private fun createJwtFilter(): HttpFilter = HttpFilter
        .newBuilder()
        .setName("envoy.filters.http.jwt_authn")
        .setTypedConfig(
            Any.pack(
                JwtAuthentication.newBuilder().putAllProviders(
                    mapOf(
                        "oauth2-mock" to JwtProvider.newBuilder()
                            .setRemoteJwks(
                                RemoteJwks.newBuilder().setHttpUri(
                                    HttpUri.newBuilder()
                                        .setUri("https://oauth2-mock.herokuapp.com/auth/jwks")
                                        .setCluster("oauth2-mock.herokuapp.com|443")
                                        .setTimeout(
                                            Duration.newBuilder().setSeconds(10).build()
                                        ).build()
                                )
                                    .setCacheDuration(Duration.newBuilder().setSeconds(300).build())
                            )
                            .setIssuer("https://oauth2-mock.herokuapp.com/auth")
                            .setForward(true)
                            .setForwardPayloadHeader(snapshotProperties.jwt.forwardPayloadHeader)
                            .setPayloadInMetadata(snapshotProperties.jwt.payloadInMetadata)
                            .build()
                    )
                )
                    .addRules(
                        RequirementRule.newBuilder().setMatch(RouteMatch.newBuilder().setPrefix("/")).setRequires(
                            JwtRequirement.newBuilder().setProviderName("oauth2-mock").build()
                        )
                    )
                    .build()
            )
        )
        .build()

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
}
