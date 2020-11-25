package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters

import io.envoyproxy.envoy.type.matcher.v3.RegexMatcher
import io.envoyproxy.envoy.type.matcher.v3.StringMatcher
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.TlsAuthenticationProperties
import java.lang.IllegalArgumentException

class SanUriMatcherFactory(
    private val tlsProperties: TlsAuthenticationProperties
) {
    private val luaEscapeRegex = Regex("""[-().%+*?\[^$]""")
    private val serviceNameTemplate = "{service-name}"

    val sanUriWildcardRegex = createSanUriWildcardRegex()
    val sanUriWildcardRegexForLua = createSanUriWildcardRegexForLua()

    private fun createSanUriWildcardRegex(): String {
        val parts = getSanUriFormatSplit()
        val prefix = Regex.escape(parts.first)
        val suffix = Regex.escape(parts.second)
        return "$prefix${tlsProperties.serviceNameWildcardRegex}$suffix"
    }

    private fun createSanUriWildcardRegexForLua(): String {
        val parts = getSanUriFormatSplit()
        val prefix = escapeForLua(parts.first)
        val suffix = escapeForLua(parts.second)
        return "^${prefix}(${tlsProperties.serviceNameWildcardRegex})${suffix}\$"
    }

    private fun escapeForLua(input: String): String {
        return input.replace(luaEscapeRegex, "%$0")
    }

    private fun getSanUriFormatSplit(): Pair<String, String> {
        val format = tlsProperties.sanUriFormat
        val parts = format.split(serviceNameTemplate)
        if (parts.size != 2) {
            throw IllegalArgumentException("SAN URI $format does not properly contain $serviceNameTemplate")
        }
        return parts[0] to parts[1]
    }

    private val sanUriRegexMatcher = RegexMatcher.newBuilder()
        .setGoogleRe2(RegexMatcher.GoogleRE2.getDefaultInstance())
        .setRegex(sanUriWildcardRegex).build()

    private val sanUriWildcardStringMatcher = StringMatcher.newBuilder()
            .setSafeRegex(sanUriRegexMatcher)
            .build()

    private fun resolveSanUri(serviceName: String, format: String): String {
        return format.replace(serviceNameTemplate, serviceName)
    }

    fun createSanUriMatcher(serviceName: String): StringMatcher {
        return if (serviceName == tlsProperties.wildcardClientIdentifier) {
            sanUriWildcardStringMatcher
        } else {
            val principalName = resolveSanUri(serviceName, tlsProperties.sanUriFormat)
            StringMatcher.newBuilder()
                .setExact(principalName)
                .build()
        }
    }
}
