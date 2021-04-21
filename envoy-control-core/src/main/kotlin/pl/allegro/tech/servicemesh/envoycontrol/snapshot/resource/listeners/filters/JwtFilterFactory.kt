package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters

import com.google.protobuf.Any
import com.google.protobuf.util.Durations
import io.envoyproxy.envoy.config.core.v3.HttpUri
import io.envoyproxy.envoy.config.route.v3.RouteMatch
import io.envoyproxy.envoy.extensions.filters.http.jwt_authn.v3.JwtAuthentication
import io.envoyproxy.envoy.extensions.filters.http.jwt_authn.v3.JwtProvider
import io.envoyproxy.envoy.extensions.filters.http.jwt_authn.v3.JwtRequirement
import io.envoyproxy.envoy.extensions.filters.http.jwt_authn.v3.RemoteJwks
import io.envoyproxy.envoy.extensions.filters.http.jwt_authn.v3.RequirementRule
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.JwtFilterProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.OAuthProvider

class JwtFilterFactory(
    private val jwtFilterProperties: JwtFilterProperties
) {

    fun createJwtFilter(): HttpFilter = HttpFilter
        .newBuilder()
        .setName("envoy.filters.http.jwt_authn")
        .setTypedConfig(
            Any.pack(
                JwtAuthentication.newBuilder().putAllProviders(
                    getOAuthProviders()
                ).addAllRules(
                    getRules()
                )
                    .build()
            )
        )
        .build()

    private fun getOAuthProviders(): Map<String, JwtProvider> =
        jwtFilterProperties.providers.associate {
            it.name to createProvider(it)
        }

    private fun createProvider(provider: OAuthProvider) = JwtProvider.newBuilder()
        .setRemoteJwks(
            RemoteJwks.newBuilder().setHttpUri(
                HttpUri.newBuilder()
                    .setUri(provider.jwksUri.toString())
                    .setCluster(provider.cluster)
                    .setTimeout(
                        Durations.fromMillis(provider.connectionTimeout.toMillis())
                    ).build()
            )
                .setCacheDuration(Durations.fromMillis(provider.cacheDuration.toMillis()))
        )
        .setIssuer(provider.name)
        .setForward(true)
        .setForwardPayloadHeader(jwtFilterProperties.forwardPayloadHeader)
        .setPayloadInMetadata(jwtFilterProperties.payloadInMetadata)
        .build()

    private fun getRules(): List<RequirementRule> = jwtFilterProperties.providers.map(this::createRule)

    // without config for now
    private fun createRule(provider: OAuthProvider) = RequirementRule.newBuilder()
        .setMatch(RouteMatch.newBuilder().setPrefix("/")).setRequires(
            JwtRequirement.newBuilder().setProviderName(provider.name).build()
        ).build()
}
