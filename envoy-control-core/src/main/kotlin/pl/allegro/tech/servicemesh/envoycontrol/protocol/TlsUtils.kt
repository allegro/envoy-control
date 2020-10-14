package pl.allegro.tech.servicemesh.envoycontrol.protocol

import io.envoyproxy.envoy.type.matcher.RegexMatcher
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.TlsAuthenticationProperties

class TlsUtils(
        tlsProperties: TlsAuthenticationProperties
) {
    private val sanUriRegex = RegexMatcher.newBuilder()
            .setGoogleRe2(RegexMatcher.GoogleRE2.getDefaultInstance())
            .setRegex(tlsProperties.sanUriWildcardRegex).build()

    fun resolveSanUriRegex(): RegexMatcher {
        return sanUriRegex
    }

    companion object {
        fun resolveSanUri(serviceName: String, format: String): String {
            return format.replace("{service-name}", serviceName)
        }
    }
}
