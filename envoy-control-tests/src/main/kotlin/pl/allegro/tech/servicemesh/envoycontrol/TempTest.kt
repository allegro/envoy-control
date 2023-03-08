package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isFrom
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isOk
import pl.allegro.tech.servicemesh.envoycontrol.config.Ads
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension

class TempTest {

    companion object {
        private val properties = mapOf(
            "envoy-control.envoy.snapshot.routing.service-tags.enabled" to true,
            "envoy-control.envoy.snapshot.routing.service-tags.metadata-key" to "tag",
        )

        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(consul, properties)

        @JvmField
        @RegisterExtension
        val service = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val envoy = EnvoyExtension(envoyControl, service, config = Ads)
    }


    // TODO: test x-envoy-upstream-remote-address in response

    @Test
    fun debug() {
        consul.server.operations.registerService(name = "echo", tags = listOf("lorem", "ipsum"), extension = service)
        consul.server.operations.registerService(name = "service-1", tags = listOf(), extension = service)
        envoy.container.addDnsEntry("myhttp.example.com", service.container().ipAddress())

        envoy.waitForReadyServices("echo", "service-1", "myhttp.example.com:5678")

        val adminAddress = envoy.container.adminUrl()

        run {
            // when
            val response = envoy.egressOperations.callService(service = "echo")

            // then
            val serviceTagsResponseHeader = response.headers["x-envoy-upstream-service-tags"]

            assertThat(response).isFrom(service)
            assertThat(serviceTagsResponseHeader).isEqualTo("""["lorem","ipsum"]""")
        }

        run {
            // when
            val response = envoy.egressOperations.callService(service = "service-1")

            // then
            val serviceTagsResponseHeader = response.headers["x-envoy-upstream-service-tags"]

            assertThat(response).isFrom(service)
            assertThat(serviceTagsResponseHeader).isNull()
        }

        run {
            // when
            val response = envoy.egressOperations.callDomain("myhttp.example.com:5678")

            // then
            val serviceTagsResponseHeader = response.headers["x-envoy-upstream-service-tags"]

            assertThat(response).isFrom(service)
            assertThat(serviceTagsResponseHeader).isNull()
        }


    }
}


class AutoServiceTagTempTest {

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
        val service = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val envoy = EnvoyExtension(
            envoyControl,
            localService = service,
            config = Ads.copy(configOverride = autoServiceTagEnabledSettings)
        )
    }

    @Test
    fun `apps not registered in consul`() {

        val adminAddress = envoy.container.adminUrl()

        run {
            val response = envoy.egressOperations.callService(service = "echo")
            val body = response.body?.string()

            assertThat(response.code).isEqualTo(503)
        }

        run {
            val response = envoy.egressOperations.callService(service = "echo", headers = mapOf("x-service-tag" to "client-side-tag"))
            val body = response.body?.string()

            assertThat(response.code).isEqualTo(503)
        }

        run {
            val response = envoy.egressOperations.callService(
                service = "echo-disabled",
                headers = mapOf("x-service-tag" to "echo-disabled-client-side-tag")
            )
            val body = response.body?.string()

            assertThat(response.code).isEqualTo(503)
        }

        run {
            val response = envoy.egressOperations.callService(
                service = "echo-one-tag",
                headers = mapOf("x-service-tag" to "echo-one-tag-client-side-tag")
            )
            val body = response.body?.string()

            assertThat(response.code).isEqualTo(503)
        }

        run {
            val response = envoy.egressOperations.callService(
                service = "echo-no-tag",
                headers = mapOf("x-service-tag" to "echo-no-tag-client-side-tag")
            )
            val body = response.body?.string()

            assertThat(response.code).isEqualTo(503)
        }

        /**

        auto_service_tag_preference is not empty!: ipsum,lorem
        request x-service-tag is nil
        serviceTagMetadataKey: tag
        metadata service tag is nil

        auto_service_tag_preference is not empty!: ipsum,lorem
        request x-service-tag: client-side-tag
        serviceTagMetadataKey: tag
        metadata service tag: client-side-tag

        auto_service_tag_preference is empty!
        request x-service-tag: echo-disabled-client-side-tag
        serviceTagMetadataKey is nil

        auto_service_tag_preference is not empty!: one
        request x-service-tag: echo-one-tag-client-side-tag
        serviceTagMetadataKey: tag
        metadata service tag: echo-one-tag-client-side-tag

        auto_service_tag_preference is empty!
        request x-service-tag: echo-no-tag-client-side-tag
        serviceTagMetadataKey: tag
        metadata service tag: echo-no-tag-client-side-tag

         */
    }

    @Test
    fun `should reject request service-tag if it duplicates service-tag-preference`() {
        // given
        consul.server.operations.registerService(name = "echo", tags = listOf("lorem", "est"), extension = service)
        envoy.waitForReadyServices("echo")

        // when
        val notDuplicatedTagResponse =
            envoy.egressOperations.callService(service = "echo", headers = mapOf("x-service-tag" to "est"))
        val duplicatedTagResponse =
            envoy.egressOperations.callService(service = "echo", headers = mapOf("x-service-tag" to "lorem"))


        val body = duplicatedTagResponse.body?.string()

        // then
        assertThat(notDuplicatedTagResponse).isOk().isFrom(service)
        assertThat(duplicatedTagResponse.code).isEqualTo(400)

        // lua:respond():
        //   - literal body
        //   - content-type: text/plain by default

    }
}
