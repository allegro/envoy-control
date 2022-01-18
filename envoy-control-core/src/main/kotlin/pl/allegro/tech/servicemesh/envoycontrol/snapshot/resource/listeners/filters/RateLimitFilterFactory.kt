package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters

import com.google.protobuf.Any
import io.envoyproxy.envoy.config.core.v3.ApiVersion
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group
import io.envoyproxy.envoy.extensions.filters.http.ratelimit.v3.RateLimit
import io.envoyproxy.envoy.config.ratelimit.v3.RateLimitServiceConfig
import io.envoyproxy.envoy.config.core.v3.GrpcService
import io.envoyproxy.envoy.extensions.filters.http.lua.v3.Lua
import pl.allegro.tech.servicemesh.envoycontrol.groups.containsGlobalRateLimits
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.RateLimitProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.getRuleId

class RateLimitFilterFactory(
    private val rateLimitProperties: RateLimitProperties
) {
    fun createGlobalRateLimitFilter(group: Group): HttpFilter? =
        if (group.proxySettings.incoming.rateLimitEndpoints.containsGlobalRateLimits()) {
            RateLimit.newBuilder()
                .setDomain(rateLimitProperties.domain)
                .setRateLimitedAsResourceExhausted(true)
                .setFailureModeDeny(false)
                .setEnableXRatelimitHeaders(RateLimit.XRateLimitHeadersRFCVersion.DRAFT_VERSION_03)
                .setRateLimitService(RateLimitServiceConfig.newBuilder()
                    .setGrpcService(GrpcService.newBuilder()
                        .setEnvoyGrpc(GrpcService.EnvoyGrpc.newBuilder()
                            .setClusterName(rateLimitProperties.serviceName)
                        )
                    )
                    .setTransportApiVersion(ApiVersion.V3)
                )
                .build()
                .let { rateLimit ->
                    HttpFilter.newBuilder()
                        .setName("envoy.filters.http.ratelimit")
                        .setTypedConfig(Any.pack(rateLimit))
                        .build()
                }
        } else {
            null
        }

    fun createLuaLimitOverrideFilter(group: Group): HttpFilter? =
        if (group.proxySettings.incoming.rateLimitEndpoints.containsGlobalRateLimits()) {
            HttpFilter.newBuilder()
                .setName("envoy.lua.ratelimit")
                .setTypedConfig(Any.pack(
                    Lua.newBuilder().setInlineCode("""
                    function envoy_on_request(request_handle)
                      ${createLuaLimitOverrideCode(group)}
                    end  
                """.trimIndent()).build()))
                .build()
        } else {
            null
        }

    private fun createLuaLimitOverrideCode(group: Group): String =
        group.proxySettings.incoming.rateLimitEndpoints.joinToString(separator = System.lineSeparator()) { endpoint ->
            val ruleId = getRuleId(group.serviceName, endpoint)
            val (requestsPerUnit, unit) = convertToFilterRateLimit(endpoint.rateLimit)

            """
             request_handle:streamInfo():dynamicMetadata():set("envoy.filters.http.ratelimit.override", "$ruleId", {
               unit = "$unit",
               requests_per_unit = $requestsPerUnit
             });                                      
            """.trimIndent()
        }

    private fun convertToFilterRateLimit(rateLimitStr: String): Pair<Int, String> =
        rateLimitStr.split("/").let {
            it[0].toInt() to when (it[1]) {
                "s" -> "SECOND"
                "m" -> "MINUTE"
                "h" -> "HOUR"
                else -> throw InternalInvalidUnitException(it[1])
            }
        }
}

private class InternalInvalidUnitException(unit: String):
    RuntimeException("Internal exception: invalid unit $unit (should be validated earlier)")
