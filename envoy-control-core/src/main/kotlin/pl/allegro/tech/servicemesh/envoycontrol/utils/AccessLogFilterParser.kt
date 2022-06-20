package pl.allegro.tech.servicemesh.envoycontrol.utils

import com.google.re2j.Pattern
import io.envoyproxy.envoy.config.accesslog.v3.ComparisonFilter
import io.envoyproxy.envoy.type.matcher.v3.RegexMatcher
import io.envoyproxy.envoy.type.matcher.v3.StringMatcher
import pl.allegro.tech.servicemesh.envoycontrol.groups.NodeMetadataValidationException

object AccessLogFilterParser {
    private const val DELIMITER: Char = ':'
    private const val FLAG_DELIMITER: Char = ','

    private val operators: Array<ComparisonFilter.Op> = arrayOf(
        ComparisonFilter.Op.LE, ComparisonFilter.Op.GE, ComparisonFilter.Op.EQ
    )
    private val flags: Array<String> = arrayOf(
        "UH", "UF", "UO", "NR", "URX", "NC", "DT", "DC", "LH", "UT", "LR", "UR",
        "UC", "DI", "FI", "RL", "UAEX", "RLSE", "IH", "SI", "DPE", "UPE", "UMSDR",
        "OM", "DF"
    )
    private val comparisonFilterPattern: Pattern = Pattern.compile(
        """^(${operators.joinToString("|")})$DELIMITER(\d{3})$"""
    )

    private val responseFlagFilterPattern: Pattern = Pattern.compile(
        """^((${flags.joinToString("|")}){1}(,(${flags.joinToString("|")}))*)$"""
    )

    private val headerFilterPattern: Pattern = Pattern.compile("""^((.+)$DELIMITER(.+))$""")

    fun parseComparisonFilter(value: String): ComparisonFilterSettings {
        if (!comparisonFilterPattern.matches(value)) {
            throw NodeMetadataValidationException(
                "Invalid access log comparison filter. Expected OPERATOR:VALUE"
            )
        }
        val split = value.split(DELIMITER)
        return ComparisonFilterSettings(
            comparisonOperator = ComparisonFilter.Op.valueOf(split[0]),
            comparisonCode = split[1].toInt()
        )
    }

    fun parseResponseFlagFilter(value: String): Iterable<String> {
        if (!responseFlagFilterPattern.matches(value)) {
            throw NodeMetadataValidationException(
                "Invalid access log response flag filter. Expected valid values separated by comma"
            )
        }
        return value.split(FLAG_DELIMITER)
    }

    fun parseHeaderFilter(value: String): HeaderFilterSettings {
        if (!headerFilterPattern.matches(value)) {
            throw NodeMetadataValidationException(
                "Invalid access log header filter. Expected HEADER_NAME:REGEX"
            )
        }
        val split = value.split(DELIMITER, limit = 2)
        return HeaderFilterSettings(
            headerName = split[0],
            regex = split[1]
        )
    }
}

data class ComparisonFilterSettings(
    val comparisonOperator: ComparisonFilter.Op,
    val comparisonCode: Int
)

data class HeaderFilterSettings(
    val headerName: String,
    val regex: String
)
