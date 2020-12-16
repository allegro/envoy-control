package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isUnreachable
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension

class LocalReplyMappingTest {

    companion object {

        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(
            consul, mapOf(
                "envoy-control.envoy.snapshot.routing.service-tags.enabled" to true,
                "envoy-control.envoy.snapshot.routing.service-tags.metadata-key" to "tag",
                "envoy-control.envoy.snapshot.dynamic-listeners.local-reply-mapper.enabled" to true,
                "envoy-control.envoy.snapshot.dynamic-listeners.local-reply-mapper.matchers[0].header-matcher.name" to ":path",
                "envoy-control.envoy.snapshot.dynamic-listeners.local-reply-mapper.matchers[0].header-matcher.exactMatch" to "/api",
                "envoy-control.envoy.snapshot.dynamic-listeners.local-reply-mapper.matchers[0].status-code-to-return" to 510,
                "envoy-control.envoy.snapshot.dynamic-listeners.local-reply-mapper.matchers[0].response-format.json-format" to mapOf(
                    "destination" to mapOf(
                        "serviceName" to "%REQ(:authority)%",
                        "serviceTag" to "%REQ(x-service-tag)%",
                        "path" to "%REQ(:path)%"
                    ),
                    "responseFlags" to "%RESPONSE_FLAGS%",
                    "body" to "%LOCAL_REPLY_BODY%",
                    "path" to "%REQ(:path)%"
                ),
                "envoy-control.envoy.snapshot.dynamic-listeners.local-reply-mapper.matchers[1].response-flag-matcher" to listOf(
                    "NR"
                ),
                "envoy-control.envoy.snapshot.dynamic-listeners.local-reply-mapper.matchers[1].status-code-to-return" to 522,
                "envoy-control.envoy.snapshot.dynamic-listeners.local-reply-mapper.matchers[1].body-to-return" to "my-custom no route body",
                "envoy-control.envoy.snapshot.dynamic-listeners.local-reply-mapper.matchers[1].response-format.text-format" to
                    "Request to service: %REQ(:authority)% responseFlags:%RESPONSE_FLAGS% body: %LOCAL_REPLY_BODY%",
                "envoy-control.envoy.snapshot.dynamic-listeners.local-reply-mapper.response-format.json-format" to mapOf(
                    "destination" to "service-name: %REQ(:authority)%, service-tag: %REQ(x-service-tag)%",
                    "responseFlags" to "%RESPONSE_FLAGS%"
                )
            )
        )

        @JvmField
        @RegisterExtension
        val service = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val envoy = EnvoyExtension(envoyControl, service)
    }

    @Test
    fun `should return 503 with body in json format`() {
        // given
        consul.server.operations.registerService(service, name = "service-1")

        // when
        untilAsserted {
            // when
            val response = envoy.egressOperations.callService(
                "service-1", headers = mapOf("x-service-tag" to "not-existing")
            )

            assertThat(
                response.body()?.string()
            ).contains("""{"responseFlags":"UH","destination":"service-name: service-1, service-tag: not-existing"}""")
            assertThat(response).isUnreachable()
        }
    }

    @Test
    fun `should map no healthy upstream to different json format and rewrite status code to 522`() {
        // when
        untilAsserted {
            // when
            val response = envoy.egressOperations.callService("service-2")

            assertThat(
                response.body()?.string()
            ).contains("Request to service: service-2 responseFlags:NR body: my-custom no route body")
            assertThat(response.code()).isEqualTo(522)
        }
    }

    @Test
    fun `should map no healthy upstream to different json format and rewrite status code to 510 when requesting api path`() {
        // when
        untilAsserted {
            // when
            val response = envoy.egressOperations.callService("service-2", pathAndQuery = "/api")

            assertThat(
                response.body()?.string()
            ).contains("""{"destination":{"serviceTag":null,"path":"/api","serviceName":"service-2"},"path":"/api","body":"","responseFlags":"NR"}""")
            assertThat(response.code()).isEqualTo(510)
        }
    }
}
