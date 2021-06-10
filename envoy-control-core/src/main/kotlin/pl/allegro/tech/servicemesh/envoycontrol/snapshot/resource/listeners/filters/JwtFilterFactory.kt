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
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group
import pl.allegro.tech.servicemesh.envoycontrol.groups.IncomingEndpoint
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.JwtFilterProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.OAuthProvider
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.ProviderName

class JwtFilterFactory(
    private val properties: JwtFilterProperties
) {

    private val jwtProviders: Map<String, JwtProvider> = getJwtProviders()
    private val selectorToOAuthProvider: Map<String, String> =
        properties.providers.entries.flatMap { (name, provider) ->
            provider.selectorToTokenField.keys.map { selector -> selector to name }
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
        return group.proxySettings.incoming.endpoints.any {
            it.oauth != null || it.clients.any { client -> client.selector in selectorToOAuthProvider.keys }
        } && properties.providers.isNotEmpty()
    }

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

        providers.addAll(endpoint.clients.filter { it.selector in selectorToOAuthProvider.keys }
            .mapNotNull { selectorToOAuthProvider[it.selector] })

        return if (providers.isNotEmpty()) {
            RequirementRule.newBuilder().setMatch(
                RouteMatch.newBuilder().setPrefix(endpoint.path)
            ).setRequires(
                createJwtRequirement(providers)
            ).build()
        } else {
            null
        }
    }

    private fun createJwtRequirement(providers: Set<String>): JwtRequirement {
        return JwtRequirement.newBuilder()
            .setRequiresAny(
                JwtRequirementOrList.newBuilder().addAllRequirements(
                    providers.map {
                        JwtRequirement.newBuilder().setProviderName(it).build()
                    }
                        .plus(JwtRequirement.newBuilder().setAllowMissingOrFailed(Empty.getDefaultInstance()).build())
                )
            )
            .build()
    }
}
