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
import pl.allegro.tech.servicemesh.envoycontrol.groups.ClientWithSelector
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group
import pl.allegro.tech.servicemesh.envoycontrol.groups.IncomingEndpoint
import pl.allegro.tech.servicemesh.envoycontrol.groups.PathMatchingType
import pl.allegro.tech.servicemesh.envoycontrol.groups.Role
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
                            .addAllRules(createRules(
                                group.proxySettings.incoming.endpoints,
                                group.proxySettings.incoming.roles))
                            .build()
                    )
                )
                .build()
        } else {
            null
        }
    }

    private fun shouldCreateFilter(group: Group): Boolean {
        if (properties.providers.isEmpty()) {
            return false
        }

        val roles = group.proxySettings.incoming.roles
        return group.proxySettings.incoming.endpoints.any { endpoint ->
            endpoint.oauth != null || containsClientsWithSelector(endpoint, roles)
        }
    }

    private fun containsClientsWithSelector(endpoint: IncomingEndpoint, roles: List<Role>): Boolean {
        return resolveClientNames(endpoint, roles).any(clientToOAuthProviderName::containsKey)
    }

    private fun resolveClientNames(endpoint: IncomingEndpoint, roles: List<Role>): Set<String> {
        if (roles.isEmpty()) {
            return endpoint.clients.map { it.name }.toSet()
        }

        return endpoint.clients.flatMap { clientOrRole ->
            roles.find { it.name == clientOrRole.name }?.clients ?: setOf(clientOrRole)
        }.map { it.name }.toSet()
    }

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

    private fun createRules(endpoints: List<IncomingEndpoint>, roles: List<Role>): Set<RequirementRule> {
        return endpoints.flatMap { createRulesForEndpoint(it, roles) }.toSet()
    }

    private fun createRulesForEndpoint(endpoint: IncomingEndpoint, roles: List<Role>): Set<RequirementRule> {
        val providers = mutableSetOf<String>()

        if (endpoint.oauth != null) {
            providers.add(endpoint.oauth.provider)
        }

        val resolvedClientNames = resolveClientNames(endpoint, roles)
        providers.addAll(resolvedClientNames.filter { it in clientToOAuthProviderName.keys }
            .mapNotNull { clientToOAuthProviderName[it] })

        if (providers.isEmpty()) {
            return emptySet()
        }

        return if (endpoint.paths.isNotEmpty()) {
            endpoint.paths.map {
                requirementRuleWithURITemplateMatching(it, endpoint.methods, providers)
            }.toSet()
        } else {
            setOf(requirementRuleWithPathMatching(
                endpoint.path, endpoint.pathMatchingType, endpoint.methods, providers))
        }
    }

    private fun requirementRuleWithURITemplateMatching(
        pathGlobPattern: String,
        methods: Set<String>,
        providers: MutableSet<String>
    ): RequirementRule {
        val pathMatching = RouteMatch.newBuilder().setPathMatchPolicy(
            TypedExtensionConfig.newBuilder()
                .setName("envoy.path.match.uri_template.uri_template_matcher")
                .setTypedConfig(
                    Any.pack(
                        UriTemplateMatchConfig.newBuilder()
                            .setPathTemplate(pathGlobPattern)
                            .build()
                    )
                ).build()
        )
        if (methods.isNotEmpty()) {
            pathMatching.addHeaders(createHeaderMatcherBuilder(methods))
        }

        return RequirementRule.newBuilder()
            .setMatch(pathMatching)
            .setRequires(createJwtRequirement(providers))
            .build()
    }

    private fun requirementRuleWithPathMatching(
        path: String,
        pathMatchingType: PathMatchingType,
        methods: Set<String>,
        providers: MutableSet<String>
    ): RequirementRule {
        val pathMatching = when (pathMatchingType) {
            PathMatchingType.PATH -> RouteMatch.newBuilder().setPath(path)
            PathMatchingType.PATH_PREFIX -> RouteMatch.newBuilder().setPrefix(path)
            PathMatchingType.PATH_REGEX -> RouteMatch.newBuilder()
                .setSafeRegex(
                    RegexMatcher.newBuilder().setRegex(path).setGoogleRe2(
                        RegexMatcher.GoogleRE2.getDefaultInstance()
                    ).build()
                )
        }
        if (methods.isNotEmpty()) {
            pathMatching.addHeaders(createHeaderMatcherBuilder(methods))
        }

        return RequirementRule.newBuilder()
            .setMatch(pathMatching)
            .setRequires(createJwtRequirement(providers))
            .build()
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
