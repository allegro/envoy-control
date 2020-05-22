package pl.allegro.tech.servicemesh.envoycontrol.groups

import com.google.re2j.Pattern
import io.envoyproxy.envoy.config.filter.accesslog.v2.ComparisonFilter

class AccessLogStatusCodeFilterFactory {
    private val operators: Array<ComparisonFilter.Op> = arrayOf(
        ComparisonFilter.Op.LE, ComparisonFilter.Op.GE, ComparisonFilter.Op.EQ
    )
    private val delimiter: Char = ':'
    private val statusCodeFilterPattern: Pattern = Pattern.compile(
        """(${operators.joinToString("|")})$delimiter(\d{3})"""
    )

    fun build(value: String): AccessLogFilterSettings.StatusCodeFilter {
        if (!statusCodeFilterPattern.matches(value)) {
            throw NodeMetadataValidationException(
                "Invalid access log status code filter. Expected OPERATOR:STATUS_CODE"
            )
        }
        val split = value.split(delimiter)
        return AccessLogFilterSettings.StatusCodeFilter(
            comparisonOperator = ComparisonFilter.Op.valueOf(split[0]),
            comparisonCode = split[1].toInt()
        )
    }
}
