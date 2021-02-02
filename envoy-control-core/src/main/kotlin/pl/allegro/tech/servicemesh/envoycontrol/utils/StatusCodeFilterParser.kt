package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.util

import com.google.re2j.Pattern
import io.envoyproxy.envoy.config.accesslog.v3.ComparisonFilter
import pl.allegro.tech.servicemesh.envoycontrol.groups.NodeMetadataValidationException

object StatusCodeFilterParser {
    private const val DELIMITER: Char = ':'

    private val operators: Array<ComparisonFilter.Op> = arrayOf(
        ComparisonFilter.Op.LE, ComparisonFilter.Op.GE, ComparisonFilter.Op.EQ
    )
    private val statusCodeFilterPattern: Pattern = Pattern.compile(
        """^(${operators.joinToString("|")})$DELIMITER(\d{3})$"""
    )

    fun parseStatusCodeFilter(value: String): StatusCodeFilterSettings {
        if (!statusCodeFilterPattern.matches(value)) {
            throw NodeMetadataValidationException(
                "Invalid access log status code filter. Expected OPERATOR:STATUS_CODE"
            )
        }
        val split = value.split(DELIMITER)
        return StatusCodeFilterSettings(
            comparisonOperator = ComparisonFilter.Op.valueOf(split[0]),
            comparisonCode = split[1].toInt()
        )
    }
}

data class StatusCodeFilterSettings(
    val comparisonOperator: ComparisonFilter.Op,
    val comparisonCode: Int
)
