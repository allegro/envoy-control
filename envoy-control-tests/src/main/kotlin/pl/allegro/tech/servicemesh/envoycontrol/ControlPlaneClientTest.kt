package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import java.net.URI

class ControlPlaneClientTest {
    companion object {
        private val properties = mapOf(
            "server.compression.enabled" to true,
            "server.compression.mime-types" to
                "application/json,application/xml,text/html,text/xml,text/plain,application/javascript,text/css",
            "server.compression.min-response-size" to "1",
            "envoy-control.sync.enabled" to true,
            "envoy-control.envoy.snapshot.routing.service-tags.enabled" to true,
            "envoy-control.envoy.snapshot.routing.service-tags.metadata-key" to "tag",
            "envoy-control.envoy.snapshot.routing.service-tags.auto-service-tag-enabled" to true,
            "envoy-control.envoy.snapshot.routing.service-tags.reject-requests-with-duplicated-auto-service-tag" to true
        )

        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(AdminRouteTest.consul, properties)
    }

    @Test
    fun `should get state`() {
        val state = envoyControl.app
            .controlPlaneClient()
            .getState(URI.create("http://localhost:${envoyControl.app.appPort}"))
            .get()
        Assertions.assertThat(state)
            .isNotNull()
            .hasNoNullFieldsOrProperties()
    }
}
