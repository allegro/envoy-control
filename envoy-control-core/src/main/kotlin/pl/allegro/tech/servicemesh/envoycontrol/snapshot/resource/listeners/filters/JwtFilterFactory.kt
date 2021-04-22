package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters

import com.google.protobuf.Any
import com.google.protobuf.Empty
import com.google.protobuf.util.Durations
import io.envoyproxy.envoy.config.core.v3.HttpUri
import io.envoyproxy.envoy.config.route.v3.RouteMatch
import io.envoyproxy.envoy.extensions.filters.http.jwt_authn.v3.JwtAuthentication
import io.envoyproxy.envoy.extensions.filters.http.jwt_authn.v3.JwtProvider
import io.envoyproxy.envoy.extensions.filters.http.jwt_authn.v3.JwtRequirement
import io.envoyproxy.envoy.extensions.filters.http.jwt_authn.v3.RemoteJwks
import io.envoyproxy.envoy.extensions.filters.http.jwt_authn.v3.RequirementRule
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group
import pl.allegro.tech.servicemesh.envoycontrol.groups.IncomingEndpoint
import pl.allegro.tech.servicemesh.envoycontrol.groups.OAuth
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.JwtFilterProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.OAuthProvider

class JwtFilterFactory(
    private val properties: JwtFilterProperties
) {

    fun createJwtFilter(group: Group): HttpFilter? {
        return if (shouldCreateFilter(group)) {
            HttpFilter.newBuilder()
                .setName("envoy.filters.http.jwt_authn")
                .setTypedConfig(
                    Any.pack(
                        JwtAuthentication.newBuilder().putAllProviders(
                            getOAuthProviders()
                        ).addAllRules(
                            createRules(group.proxySettings.incoming.endpoints)
                        )
                            .build()
                    )
                )
                .build()
        } else {
            null
        }
    }

    private fun shouldCreateFilter(group: Group): Boolean {
        return group.proxySettings.incoming.endpoints.any { it.oauth != null } && properties.providers.isNotEmpty()
    }

    private fun getOAuthProviders(): Map<String, JwtProvider> =
        properties.providers.associate {
            it.name to createProvider(it)
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
        .setIssuer(provider.name)
        .setForward(true)
        .setForwardPayloadHeader(properties.forwardPayloadHeader)
        .setPayloadInMetadata(properties.payloadInMetadata)
        .build()

    // without config for now
    private fun createRules(endpoints: List<IncomingEndpoint>): List<RequirementRule> {
        return endpoints.mapNotNull(this::createRuleForEndpoint)
    }

    private fun createRuleForEndpoint(endpoint: IncomingEndpoint): RequirementRule? {
        return if (endpoint.oauth != null) {
            RequirementRule.newBuilder().setMatch(
                RouteMatch.newBuilder().setPrefix(endpoint.path)
            ).setRequires(
                createJwtRequirement(endpoint.oauth.provider, endpoint.oauth.policy)
            ).build()
        } else {
            null
        }
    }

    private fun createJwtRequirement(provider: String, policy: OAuth.Policy): JwtRequirement {
        return when (policy) {
            OAuth.Policy.ALLOW_MISSING -> {
                JwtRequirement.newBuilder().setProviderName(provider)
                    .setAllowMissing(Empty.getDefaultInstance()).build()
            }
            OAuth.Policy.ALLOW_MISSING_OR_FAILED -> {
                JwtRequirement.newBuilder().setProviderName(provider)
                    .setAllowMissingOrFailed(Empty.getDefaultInstance()).build()
            }
            OAuth.Policy.STRICT -> {
                JwtRequirement.newBuilder().setProviderName(provider).build()
            }
        }
    }
}
