package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.WaitAllStrategy
import pl.allegro.tech.servicemesh.envoycontrol.config.Ads
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.GenericServiceExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.HttpsEchoContainer
import pl.allegro.tech.servicemesh.envoycontrol.config.service.asHttpsEchoResponse
import pl.allegro.tech.servicemesh.envoycontrol.config.testcontainers.GenericContainer

class TempTest {
    companion object {

        private val properties = mapOf(
            "envoy-control.envoy.snapshot.routing.service-tags.enabled" to true,
            "envoy-control.envoy.snapshot.routing.service-tags.metadata-key" to "tag",
            "envoy-control.envoy.snapshot.routing.service-tags.auto-service-tag-enabled" to true
        )

        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(consul, properties)

        // language=yaml
        private var autoServiceTagEnabledSettings = """
            node:
              metadata:
                proxy_settings:
                  outgoing:
                    routingPolicy:
                      autoServiceTag: true
                      serviceTagPreference: ["ipsum", "lorem"]
                    dependencies:
                      - service: "echo" 
                      - service: "echo-disabled"
                        routingPolicy:
                          autoServiceTag: false
                      - service: "echo-one-tag"  
                        routingPolicy:
                          serviceTagPreference: ["one"]
                      - service: "echo-no-tag"  
                        routingPolicy:
                          serviceTagPreference: []
                      
        """.trimIndent()

        @JvmField
        @RegisterExtension
        val service = GenericServiceExtension(HttpsEchoContainer())

        @JvmField
        @RegisterExtension
        val envoy = EnvoyExtension(envoyControl, config = Ads.copy(configOverride = autoServiceTagEnabledSettings))
    }

    @Test
    fun debug() {

        val debugContainer = GenericContainer("subfuzion/netcat").withNetwork(Network.SHARED)
            .withCommand("-l 8888")
            .waitingFor(WaitAllStrategy())
        debugContainer.start()

        val gatewayIp = envoy.container.gatewayIp()
        //val hostResolve = envoy.container.execInContainer("sh", "-c", "ping -c 1 host.testcontainers.internal")

        consul.server.operations.registerService(
            name = "echo",
            tags = listOf("lorem", "ipsum"),
            address = debugContainer.ipAddress(),
            port = 9789
        )
        envoy.waitForReadyServices("echo")

        val adminAddress = envoy.container.adminUrl()

        run {
            val response = envoy.egressOperations.callService(service = "echo")
            val echoResponse = response.asHttpsEchoResponse()
            /*
            from HttpsEchoContainer response:
              "headers": {
                "host": "echo",
                "accept-encoding": "gzip",
                "user-agent": "okhttp/4.9.0",
                "x-forwarded-proto": "http",
                "x-request-id": "99cc23ea-1f39-4955-94f4-8908f1cbcbf2",
                "x-envoy-expected-rq-timeout-ms": "120000",
                "x-service-tag-preference": "ipsum,lorem",
                "x-service-tag-preference-overwrite": "lorem",
                "x-service-tag-preference-append": "ipsum, lorem"
              },

            raw request to upstream 'echo' recorded by netcat:
GET / HTTP/1.1
host: echo
accept-encoding: gzip
user-agent: okhttp/4.9.0
x-forwarded-proto: http
x-request-id: 57a98204-a72f-452d-ba49-67823abb6847
x-envoy-expected-rq-timeout-ms: 120000
x-service-tag-preference: ipsum,lorem
x-service-tag-preference-overwrite: lorem
x-service-tag-preference-append: ipsum
x-service-tag-preference-append: lorem

             */
            assertThat(response.code).isEqualTo(503)
        }

    }
}
