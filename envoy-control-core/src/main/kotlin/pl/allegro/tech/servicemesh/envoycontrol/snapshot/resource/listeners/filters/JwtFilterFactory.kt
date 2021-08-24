package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters

import com.google.protobuf.Any
import com.google.protobuf.Empty
import com.google.protobuf.util.Durations
import io.envoyproxy.envoy.config.core.v3.HttpUri
import io.envoyproxy.envoy.config.route.v3.RouteMatch
import io.envoyproxy.envoy.extensions.filters.http.jwt_authn.v3.JwtAuthentication
import io.envoyproxy.envoy.extensions.filters.http.jwt_authn.v3.JwtProvider
import io.envoyproxy.envoy.extensions.filters.http.jwt_authn.v3.JwtRequirement
import io.envoyproxy.envoy.extensions.filters.http.jwt_authn.v3.JwtRequirementOrList
import io.envoyproxy.envoy.extensions.filters.http.jwt_authn.v3.RemoteJwks
import io.envoyproxy.envoy.extensions.filters.http.jwt_authn.v3.RequirementRule
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter
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

    private val jwtProviders: Map<ProviderName, JwtProvider> = getJwtProviders()
    private val clientToOAuthProviderName: Map<String, String> =
        properties.providers.entries.flatMap { (providerName, provider) ->
            provider.matchings.keys.map { client -> client to providerName }
        }.toMap()

    fun createJwtFilter(group: Group): HttpFilter? {
         return if (shouldCreateFilter(group)) {
            HttpFilter.newBuilder()
                .setName("envoy.filters.http.jwt_authn")
                .setTypedConfig(
                    Any.pack(
                        JwtAuthentication.newBuilder().putAllProviders(
                            jwtProviders
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

    private fun getJwtProviders(): Map<ProviderName, JwtProvider> =
        properties.providers.entries.associate {
            it.key to createProvider(it.value)
        }

    private fun createProvider(provider: OAuthProvider) = JwtProvider.newBuilder()
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
        .build()

    private fun createRules(endpoints: List<IncomingEndpoint>): Set<RequirementRule> {
        return endpoints.mapNotNull(this::createRuleForEndpoint).toSet()
    }

    private fun createRuleForEndpoint(endpoint: IncomingEndpoint): RequirementRule? {
        val providers = mutableSetOf<String>()

        if (endpoint.oauth != null) {
            providers.add(endpoint.oauth.provider)
        }

        providers.addAll(endpoint.clients.filter { it.name in clientToOAuthProviderName.keys }
            .mapNotNull { clientToOAuthProviderName[it.name] })

        return if (providers.isNotEmpty()) {
            requirementRuleWithPathMatching(endpoint.path, endpoint.pathMatchingType, providers)
        } else {
            null
        }
    }

    private fun requirementRuleWithPathMatching(
        path: String,
        pathMatchingType: PathMatchingType,
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
        return RequirementRule.newBuilder()
            .setMatch(pathMatching)
            .setRequires(createJwtRequirement(providers)).build()
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
