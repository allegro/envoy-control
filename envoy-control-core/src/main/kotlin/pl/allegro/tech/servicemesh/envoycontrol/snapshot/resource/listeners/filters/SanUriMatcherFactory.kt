package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters

import io.envoyproxy.envoy.type.matcher.v3.RegexMatcher
import io.envoyproxy.envoy.type.matcher.v3.StringMatcher
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.TlsAuthenticationProperties
import java.lang.IllegalArgumentException

class SanUriMatcherFactory(
    private val tlsProperties: TlsAuthenticationProperties
) {
    private val serviceNameTemplate = "{service-name}"
    val sanUriWildcardRegex = createSanUriWildcardRegex(tlsProperties.sanUriFormat)

    private fun createSanUriWildcardRegex(sanUriFormat: String): String {
        val parts = sanUriFormat.split(serviceNameTemplate)
        if (parts.size != 2) {
            throw IllegalArgumentException("SAN URI $sanUriFormat does not properly contain $serviceNameTemplate")
        }
        val prefix = Regex.escape(parts[0])
        val suffix = Regex.escape(parts[1])
        return "$prefix${tlsProperties.serviceNameWildcardRegex}$suffix"
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
