package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters

import com.google.protobuf.Any
import com.google.protobuf.Empty
import com.google.protobuf.util.Durations
import io.envoyproxy.envoy.config.core.v3.HttpUri
import io.envoyproxy.envoy.config.core.v3.TypedExtensionConfig
import io.envoyproxy.envoy.config.route.v3.HeaderMatcher
import io.envoyproxy.envoy.config.route.v3.RouteMatch
import io.envoyproxy.envoy.extensions.filters.http.jwt_authn.v3.JwtAuthentication
import io.envoyproxy.envoy.extensions.filters.http.jwt_authn.v3.JwtProvider
import io.envoyproxy.envoy.extensions.filters.http.jwt_authn.v3.JwtRequirement
import io.envoyproxy.envoy.extensions.filters.http.jwt_authn.v3.JwtRequirementOrList
import io.envoyproxy.envoy.extensions.filters.http.jwt_authn.v3.RemoteJwks
import io.envoyproxy.envoy.extensions.filters.http.jwt_authn.v3.RequirementRule
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter
import io.envoyproxy.envoy.extensions.path.match.uri_template.v3.UriTemplateMatchConfig
import io.envoyproxy.envoy.type.matcher.v3.RegexMatcher
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group
import pl.allegro.tech.servicemesh.envoycontrol.groups.IncomingEndpoint
import pl.allegro.tech.servicemesh.envoycontrol.groups.PathMatchingType
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.JwtFilterProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.OAuthProvider
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.ProviderName

class JwtFilterFactory(
    private val properties: JwtFilterProperties
) {

    private val jwtProviders: Map<ProviderName, JwtProvider> = getJwtProviders(failedStatusInMetadataEnabled = false)
    private val jwtProvidersWithJwtStatusMetadata: Map<ProviderName, JwtProvider> =
        getJwtProviders(failedStatusInMetadataEnabled = true)
    private val clientToOAuthProviderName: Map<String, String> =
        properties.providers.entries.flatMap { (providerName, provider) ->
            provider.matchings.keys.map { client -> client to providerName }
        }.toMap()

    fun createJwtFilter(group: Group): HttpFilter? {
        val selectedJwtProviders =
            if (group.listenersConfig?.addJwtFailureStatus != false && properties.failedStatusInMetadataEnabled) {
                jwtProvidersWithJwtStatusMetadata
            } else {
                jwtProviders
            }

        return if (shouldCreateFilter(group)) {
            HttpFilter.newBuilder()
                .setName("envoy.filters.http.jwt_authn")
                .setTypedConfig(
                    Any.pack(
                        JwtAuthentication.newBuilder().putAllProviders(
                            selectedJwtProviders
                        )
                            .addAllRules(createRules(group.proxySettings.incoming.endpoints))
                            .build()
                    )
                )
                .build()
        } else {
            null
        }
    }

    private fun shouldCreateFilter(group: Group): Boolean {
        return properties.providers.isNotEmpty() && group.proxySettings.incoming.endpoints.any {
            it.oauth != null || containsClientsWithSelector(it)
        }
    }

    private fun containsClientsWithSelector(it: IncomingEndpoint) =
        clientToOAuthProviderName.keys.intersect(it.clients.map { it.name }).isNotEmpty()

    private fun getJwtProviders(failedStatusInMetadataEnabled: Boolean): Map<ProviderName, JwtProvider> =
        properties.providers.entries.associate {
            it.key to createProvider(it.value, failedStatusInMetadataEnabled)
        }

    private fun createProvider(provider: OAuthProvider, failedStatusInMetadataEnabled: Boolean): JwtProvider {
        val jwtProvider = JwtProvider.newBuilder()
            .setRemoteJwks(
                RemoteJwks.newBuilder().setHttpUri(
                    HttpUri.newBuilder()
                        .setUri(provider.jwksUri.toString())
                        .setCluster(provider.clusterName)
                        .setTimeout(
                            Durations.fromMillis(provider.connectionTimeout.toMillis())
                        ).build()
                )
                    .setCacheDuration(Durations.fromMillis(provider.cacheDuration.toMillis()))
            )
            .setForward(properties.forwardJwt)
            .setForwardPayloadHeader(properties.forwardPayloadHeader)
            .setPayloadInMetadata(properties.payloadInMetadata)

        if (failedStatusInMetadataEnabled) {
            jwtProvider.setFailedStatusInMetadata(properties.failedStatusInMetadata)
        }

        return jwtProvider.build()
    }

    private fun createRules(endpoints: List<IncomingEndpoint>): Set<RequirementRule> {
        return endpoints.flatMap(this::createRulesForEndpoint).toSet()
    }

    private fun createRulesForEndpoint(endpoint: IncomingEndpoint): Set<RequirementRule> {
        val providers = mutableSetOf<String>()

        if (endpoint.oauth != null) {
            providers.add(endpoint.oauth.provider)
        }

        providers.addAll(endpoint.clients.filter { it.name in clientToOAuthProviderName.keys }
            .mapNotNull { clientToOAuthProviderName[it.name] })

        if (providers.isEmpty()) {
            return emptySet()
        }

        return requirementRuleWithPathMatching(endpoint, endpoint.methods, providers)
    }
    private fun requirementRuleWithPathMatching(
        endpoint: IncomingEndpoint,
        methods: Set<String>,
        providers: MutableSet<String>
    ): Set<RequirementRule> {
        val pathMatching = when (endpoint.pathMatchingType) {
            PathMatchingType.PATH -> listOf(RouteMatch.newBuilder().setPath(endpoint.path))
            PathMatchingType.PATH_PREFIX -> listOf(RouteMatch.newBuilder().setPrefix(endpoint.path))
            PathMatchingType.PATH_REGEX -> listOf(
                RouteMatch.newBuilder()
                    .setSafeRegex(
                        RegexMatcher.newBuilder().setRegex(endpoint.path).setGoogleRe2(
                            RegexMatcher.GoogleRE2.getDefaultInstance()
                        ).build()
                    )
            )

            PathMatchingType.PATHS -> endpoint.paths.map { path ->
                RouteMatch.newBuilder().setPathMatchPolicy(
                    TypedExtensionConfig.newBuilder()
                        .setName("envoy.path.match.uri_template.uri_template_matcher")
                        .setTypedConfig(
                            Any.pack(
                                UriTemplateMatchConfig.newBuilder()
                                    .setPathTemplate(path)
                                    .build()
                            )
                        ).build()
                )
            }
        }

        if (methods.isNotEmpty()) {
            pathMatching.forEach { it.addHeaders(createHeaderMatcherBuilder(methods)) }
        }

        return pathMatching.map {
            RequirementRule.newBuilder()
                .setMatch(it)
                .setRequires(createJwtRequirement(providers))
                .build()
        }.toSet()
    }

    private fun createHeaderMatcherBuilder(methods: Set<String>): HeaderMatcher.Builder {
        return HeaderMatcher.newBuilder()
            .setName(":method")
            .setSafeRegexMatch(
                RegexMatcher.newBuilder().setRegex(methods.joinToString("|")).setGoogleRe2(
                    RegexMatcher.GoogleRE2.getDefaultInstance()
                ).build()
            )
    }

    private val requirementsForProviders: Map<ProviderName, JwtRequirement> =
        jwtProviders.keys.associateWith { JwtRequirement.newBuilder().setProviderName(it).build() }

    private val allowMissingOrFailedRequirement =
        JwtRequirement.newBuilder().setAllowMissingOrFailed(Empty.getDefaultInstance()).build()

    private fun createJwtRequirement(providers: Set<String>): JwtRequirement {
        return JwtRequirement.newBuilder()
            .setRequiresAny(
                JwtRequirementOrList.newBuilder().addAllRequirements(
                    providers.map {
                        requirementsForProviders[it]
                    }
                        .plus(allowMissingOrFailedRequirement)
                )
            )
            .build()
    }
}
