package pl.allegro.tech.servicemesh.envoycontrol.routing

import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension

class ServiceTagsAndCanaryTest : ServiceTagsAndCanaryTestBase {
    companion object {

        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(consul, mapOf(
                "envoy-control.envoy.snapshot.routing.service-tags.enabled" to true,
                "envoy-control.envoy.snapshot.routing.service-tags.metadata-key" to "tag",
                "envoy-control.envoy.snapshot.load-balancing.canary.enabled" to true,
                "envoy-control.envoy.snapshot.load-balancing.canary.metadata-key" to "canary",
                "envoy-control.envoy.snapshot.load-balancing.canary.metadata-value" to "1"
        ))

        @JvmField
        @RegisterExtension
        val envoy = EnvoyExtension(envoyControl)
    }

    override fun consul() = consul

    override fun envoyControl() = envoyControl

    override fun envoy() = envoy
}
