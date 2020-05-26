package pl.allegro.tech.servicemesh.envoycontrol.groups

import com.google.re2j.Pattern
import io.envoyproxy.envoy.api.v2.core.RuntimeUInt32
import io.envoyproxy.envoy.config.filter.accesslog.v2.AccessLogFilter
import io.envoyproxy.envoy.config.filter.accesslog.v2.ComparisonFilter
import io.envoyproxy.envoy.config.filter.accesslog.v2.AccessLog.Builder
import io.envoyproxy.envoy.config.filter.accesslog.v2.StatusCodeFilter.newBuilder

class AccessLogFilterFactory {
    private val operators: Array<ComparisonFilter.Op> = arrayOf(
        ComparisonFilter.Op.LE, ComparisonFilter.Op.GE, ComparisonFilter.Op.EQ
    )
    private val delimiter: Char = ':'
    private val statusCodeFilterPattern: Pattern = Pattern.compile(
        """^(${operators.joinToString("|")})$delimiter(\d{3})$"""
    )

    fun parseStatusCodeFilter(value: String): AccessLogFilterSettings.StatusCodeFilterSettings {
        if (!statusCodeFilterPattern.matches(value)) {
            throw NodeMetadataValidationException(
                "Invalid access log status code filter. Expected OPERATOR:STATUS_CODE"
            )
        }
        val split = value.split(delimiter)
        return AccessLogFilterSettings.StatusCodeFilterSettings(
            comparisonOperator = ComparisonFilter.Op.valueOf(split[0]),
            comparisonCode = split[1].toInt()
        )
    }

    fun buildAccessLogStatusCodeFilter(builder: Builder, settings: AccessLogFilterSettings.StatusCodeFilterSettings) {
        builder.setFilter(
            AccessLogFilter.newBuilder().setStatusCodeFilter(
                newBuilder()
                    .setComparison(
                        ComparisonFilter.newBuilder()
                            .setOp(settings.comparisonOperator)
                            .setValue(
                                RuntimeUInt32.newBuilder()
                                    .setDefaultValue(settings.comparisonCode)
                                    .setRuntimeKey("access_log_filter_http_code")
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
        )
    }
}
