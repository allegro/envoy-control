package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters

import com.google.protobuf.Any
import com.google.protobuf.Empty
import com.google.protobuf.util.Durations
import io.envoyproxy.envoy.config.core.v3.HttpUri
import io.envoyproxy.envoy.config.route.v3.RouteMatch
import io.envoyproxy.envoy.extensions.filters.http.jwt_authn.v3.JwtAuthentication
import io.envoyproxy.envoy.extensions.filters.http.jwt_authn.v3.JwtProvider
import io.envoyproxy.envoy.extensions.filters.http.jwt_authn.v3.JwtRequirement
import io.envoyproxy.envoy.extensions.filters.http.jwt_authn.v3.JwtRequirementAndList
import io.envoyproxy.envoy.extensions.filters.http.jwt_authn.v3.JwtRequirementOrList
import io.envoyproxy.envoy.extensions.filters.http.jwt_authn.v3.RemoteJwks
import io.envoyproxy.envoy.extensions.filters.http.jwt_authn.v3.RequirementRule
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group
import pl.allegro.tech.servicemesh.envoycontrol.groups.IncomingEndpoint
import pl.allegro.tech.servicemesh.envoycontrol.groups.OAuth
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.JwtFilterProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.OAuthProvider
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.ProviderName

class JwtFilterFactory(
    private val properties: JwtFilterProperties
) {

    private val jwtProviders: Map<String, JwtProvider> = getJwtProviders()
    private val providersPolicyToJwtRequirements: Map<Pair<String, OAuth.Policy>, JwtRequirement> =
        createProviderPolicyToJwtRequirements()

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
        return group.proxySettings.incoming.endpoints.any { it.oauth != null } && properties.providers.isNotEmpty()
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
        .setIssuer(provider.issuer)
        .setForward(properties.forwardJwt)
        .setForwardPayloadHeader(properties.forwardPayloadHeader)
        .setPayloadInMetadata(properties.payloadInMetadata)
        .build()

    private fun createRules(endpoints: List<IncomingEndpoint>): List<RequirementRule> {
        return endpoints.mapNotNull(this::createRuleForEndpoint)
    }

    private fun createRuleForEndpoint(endpoint: IncomingEndpoint): RequirementRule? {
        return if (endpoint.oauth != null) {
            RequirementRule.newBuilder().setMatch(
                RouteMatch.newBuilder().setPrefix(endpoint.path)
            ).setRequires(
                providersPolicyToJwtRequirements[endpoint.oauth.provider to endpoint.oauth.policy]
            ).build()
        } else {
            null
        }
    }

    private fun createProviderPolicyToJwtRequirements(): Map<Pair<String, OAuth.Policy>, JwtRequirement> {
        return properties.providers.keys.flatMap { providerName ->
            OAuth.Policy.values()
                .map { policy -> Pair(providerName, policy) to createJwtRequirement(providerName) }
        }.associateBy({ it.first }, { it.second })
    }

    private fun createJwtRequirement(provider: String): JwtRequirement {
        return JwtRequirement.newBuilder()
           // .setAllowMissing(Empty.getDefaultInstance())
           //  .setProviderName(provider)
            .setRequiresAny(
                    JwtRequirementOrList.newBuilder().addAllRequirements(
                        listOf(
                            JwtRequirement.newBuilder().setProviderName(provider).build(),
                            JwtRequirement.newBuilder().setAllowMissing(Empty.getDefaultInstance()).build()
                        )
                    )
                )
            .build()
    }
}
