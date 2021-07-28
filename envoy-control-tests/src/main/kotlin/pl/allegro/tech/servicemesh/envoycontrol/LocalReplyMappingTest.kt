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
        private const val prefix = "envoy-control.envoy.snapshot"
        private const val localReplyPrefix = "$prefix.dynamic-listeners.local-reply-mapper"

        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(
            consul, mapOf(
                "$prefix.routing.service-tags.enabled" to true,
                "$prefix.routing.service-tags.metadata-key" to "tag",
                "$localReplyPrefix.enabled" to true,
                "$localReplyPrefix.matchers[0].header-matcher.name" to ":path",
                "$localReplyPrefix.matchers[0].header-matcher.exactMatch" to "/api",
                "$localReplyPrefix.matchers[0].status-code-to-return" to 510,
                "$localReplyPrefix.matchers[0].response-format.json-format" to """{
                    "destination":{
                        "serviceName":"%REQ(:authority)%",
                        "serviceTag":"%REQ(x-service-tag)%",
                        "path":"%REQ(:path)%"
                    },
                    "responseFlags":"%RESPONSE_FLAGS%",
                    "body":"%LOCAL_REPLY_BODY%",
                    "path":"%REQ(:path)%"
                }""",
                "$localReplyPrefix.matchers[1].response-flag-matcher" to listOf(
                    "NC"
                ),
                "$localReplyPrefix.matchers[1].status-code-to-return" to 522,
                "$localReplyPrefix.matchers[1].body-to-return" to "my-custom no route body",
                "$localReplyPrefix.matchers[1].response-format.text-format" to
                    "Request to service: %REQ(:authority)% responseFlags:%RESPONSE_FLAGS% body: %LOCAL_REPLY_BODY%",
                "$localReplyPrefix.response-format.content-type" to "application/envoy+json",
                "$localReplyPrefix.response-format.json-format" to """{
                    "destination":"service-name: %REQ(:authority)%, service-tag: %REQ(x-service-tag)%",
                    "responseFlags":"%RESPONSE_FLAGS%",
                    "body":"%LOCAL_REPLY_BODY%"
                }"""
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
            ).contains("""{"body":"no healthy upstream","responseFlags":"UH","destination":"service-name: service-1, service-tag: not-existing"}""")
            assertThat(response.header("content-type")).isEqualTo("application/envoy+json")
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
            ).contains("Request to service: service-2 responseFlags:NC body: my-custom no route body")
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
            ).contains("""{"destination":{"serviceTag":null,"path":"/api","serviceName":"service-2"},"path":"/api","body":"","responseFlags":"NC"}""")
            assertThat(response.code()).isEqualTo(510)
        }
    }
}
