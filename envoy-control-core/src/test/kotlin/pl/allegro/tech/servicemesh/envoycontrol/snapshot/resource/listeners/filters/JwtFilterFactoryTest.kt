package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.groups.CommunicationMode
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group
import pl.allegro.tech.servicemesh.envoycontrol.groups.Incoming
import pl.allegro.tech.servicemesh.envoycontrol.groups.IncomingEndpoint
import pl.allegro.tech.servicemesh.envoycontrol.groups.OAuth
import pl.allegro.tech.servicemesh.envoycontrol.groups.ProxySettings
import pl.allegro.tech.servicemesh.envoycontrol.groups.ServicesGroup
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.JwtFilterProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.OAuthProvider
import java.net.URI

internal class JwtFilterFactoryTest {
    private val jwtFilterFactory = JwtFilterFactory(
        JwtFilterProperties().also {
            it.providers = listOf(
                OAuthProvider("provider", URI.create("http://provider/jwks"), "provider-cluster")
            )
        }
    )
    private val multiProviderJwtFilterFactory = JwtFilterFactory(
        JwtFilterProperties().also {
            it.providers = listOf(
                OAuthProvider("provider1", URI.create("http://provider1/jwks"), "provider1-cluster"),
                OAuthProvider("provider2", URI.create("http://provider2/jwks"), "provider2-cluster")
            )
        }
    )
    private val noProviderJwtFilterFactory = JwtFilterFactory(JwtFilterProperties().also { it.providers = emptyList() })

    private val emptyGroup: Group = ServicesGroup(CommunicationMode.ADS)

    @Test
    fun `should not create JWT filter when no providers are defined`() {
        // given
        val group: Group = createGroup(mapOf("/" to "oauth-provider"))

        // when
        val filter = noProviderJwtFilterFactory.createJwtFilter(group)

        // then
        assertThat(filter).isNull()
    }

    @Test
    fun `should not create JWT filter when group contains no endpoint with oauth`() {
        // when
        val filter = jwtFilterFactory.createJwtFilter(emptyGroup)

        // then
        assertThat(filter).isNull()
    }

    @Test
    fun `should create JWT filter`() {
        // given
        val group: Group = createGroup(mapOf("/" to "provider"))

        // when
        val filter = jwtFilterFactory.createJwtFilter(group)

        // then
        assertThat(filter).isNotNull
    }

    private fun createGroup(pathToProvider: Map<String, String>) = ServicesGroup(
        CommunicationMode.ADS, proxySettings = ProxySettings(
            Incoming(
                pathToProvider.map { (path, provider) -> IncomingEndpoint(path, oauth = OAuth(provider)) }
            )
        )
    )
}
