package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.TlsAuthenticationProperties

internal class SanUriMatcherFactoryTest {

    @Test
    fun `should create SAN URI wildcard matcher regex`() {
        // when
        val factory = SanUriMatcherFactory(TlsAuthenticationProperties().also {
            it.sanUriFormat = "spiffe://{service-name}?env=dev"
            it.serviceNameWildcardRegex = ".+"
        })

        // then
        assertThat(factory.sanUriWildcardRegex).isEqualTo("dupa")
    }
}
