package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters

import io.envoyproxy.envoy.type.matcher.RegexMatcher
import io.envoyproxy.envoy.type.matcher.StringMatcher
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.TlsAuthenticationProperties
import java.lang.IllegalArgumentException

class SanUriMatcherFactory(
    private val tlsProperties: TlsAuthenticationProperties
) {
    private val serviceNameTemplate = "{service-name}"
    private val sanUriWildcardRegex = escapeRegex(tlsProperties.sanUriFormat)

    private fun escapeRegex(sanUriFormat: String): String {
        val parts = sanUriFormat.split(serviceNameTemplate)
        if (parts.size != 2) {
            throw IllegalArgumentException("SAN URI $sanUriFormat does not properly contain $serviceNameTemplate")
        }
        val prefix = maybeWrap(parts[0])
        val suffix = maybeWrap(parts[1])
        return "$prefix${tlsProperties.serviceNameWildcardRegex}$suffix"
    }

    private fun maybeWrap(part: String) = if (part != "") """\Q$part\E""" else ""

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
