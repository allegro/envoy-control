package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters

import com.google.protobuf.Any
import io.envoyproxy.envoy.api.v2.core.Metadata
import io.envoyproxy.envoy.config.filter.http.header_to_metadata.v2.Config
import io.envoyproxy.envoy.config.filter.network.http_connection_manager.v2.HttpFilter
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
    private val luaClientFilterFactory = LuaClientFilterFactory(
        snapshotProperties.incomingPermissions
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
    private val defaultRbacFilter = {
        group: Group, snapshot: GlobalSnapshot -> rbacFilterFactory.createHttpFilter(group, snapshot)
    }
    private val defaultRbacLoggingFilter = {
        group: Group, _: GlobalSnapshot -> luaFilterFactory.ingressRbacLoggingFilter(group)
    }

    private val defaultHeaderFilter = {
        group: Group, _: GlobalSnapshot -> luaClientFilterFactory.ingressClientFilter(group)
    }

    val defaultEgressFilters = listOf(defaultHeaderToMetadataFilter, defaultEnvoyRouterHttpFilter)

    /**
     * Order matters:
     * * defaultRbacLoggingFilter has to be before defaultRbacFilter, otherwise unauthorised requests will not be
     *   logged, because the RBAC filter will stop filter chain execution and subsequent filters will not process
     *   the request
     * * defaultEnvoyRouterHttpFilter - router filter should be always the last filter.
     */
    val defaultIngressFilters = listOf(
        defaultHeaderFilter, defaultRbacLoggingFilter, defaultRbacFilter, defaultEnvoyRouterHttpFilter
    )
    val defaultIngressMetadata: Metadata = luaFilterFactory.ingressRbacLoggingMetadata()

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
        .setName("envoy.router")
        .build()

    private fun headerToMetadataHttpFilter(headerToMetadataConfig: Config.Builder): HttpFilter {
        return HttpFilter.newBuilder()
                .setName("envoy.filters.http.header_to_metadata")
                .setTypedConfig(Any.pack(
                        headerToMetadataConfig.build()
                ))
                .build()
    }
}
