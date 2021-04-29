package pl.al.tech.servicemesh.envoycontrol.permissions

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.hasOneAccessAllowedWithActionLog
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isOk
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo1EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo2EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyContainer
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.EndpointMatch

internal class IncomingPermissionsDebugTest {
    companion object {

        // language=yaml
        private val echoConfig = Echo1EnvoyAuthConfig.copy(
            configOverride = """
            node:
              metadata:
                proxy_settings:
                  incoming:
                    endpoints:
                      - pathPrefix: "/ca"
                        methods: [ GET, POST, PUT ]
                        clients: [ ]
                        unlistedClientsPolicy: log
                      - pathPrefix: "/co"
                        methods: [ GET, POST, PUT ]
                        clients: [ pf ]
                        unlistedClientsPolicy: log
                      - pathPrefix: "/ev/"
                        methods: [ GET, POST ]
                        clients: [ ahc ]
                        unlistedClientsPolicy: log
                      - pathPrefix: "/he/psci"
                        methods: [ POST ]
                        clients: [ ahc ]
                        unlistedClientsPolicy: log
                      - pathPrefix: "/ia"
                        methods: [ GET, POST ]
                        clients: [ ed-se, ed-se-ad, op-co ]
                        unlistedClientsPolicy: log
                      - pathPrefix: "/iad"
                        methods: [ GET ]
                        clients: [ ed-se, ed-se-ad, op-co ]
                        unlistedClientsPolicy: log
                      - pathPrefix: "/il"
                        methods: [ GET, POST ]
                        clients: [ ed-se, ed-se-ad, pa-in-co ]
                        unlistedClientsPolicy: log
                      - pathPrefix: "/pa/ev/"
                        methods: [ POST ]
                        clients: [ ahc ]
                        unlistedClientsPolicy: log
                      - pathPrefix: "/pa"
                        methods: [ POST, PUT ]
                        clients: [ ]
                        unlistedClientsPolicy: log
                      # THIS IS THE PROBLEMATIC ENDPOINT
                      - pathPrefix: "/pa-al-in/ad/pu/"
                        methods: [ POST ]
                        clients: [ ]
                        unlistedClientsPolicy: log
                      - pathPrefix: "/po-sa"
                        methods: [ POST ]
                        clients: [ op-co ]
                        unlistedClientsPolicy: log
                      - pathPrefix: "/pu/ev/"
                        methods: [ POST ]
                        clients: [ ahc ]
                        unlistedClientsPolicy: log
                      - pathPrefix: "/pu"
                        methods: [ GET, POST ]
                        clients: [ ed-se, ed-se-ad, op-co, ahc, tr-no ]
                        unlistedClientsPolicy: log
                    unlistedEndpointsPolicy: log
        """.trimIndent()
        )

        // language=yaml
        private val echo2Config = Echo2EnvoyAuthConfig.copy(
            configOverride = """
            node:
              metadata:
                proxy_settings:
                  incoming:
                    endpoints: 
                    - path: /logged-for-all
                      clients: []
                      unlistedClientsPolicy: log
                  outgoing:
                    dependencies:
                    - service: "echo"
        """.trimIndent()
        )

        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(
            consul, mapOf(
                "envoy-control.envoy.snapshot.routes.status.create-virtual-cluster" to true,
                "envoy-control.envoy.snapshot.routes.status.endpoints" to mutableListOf(EndpointMatch().also { it.path = "/status/" }),
                "envoy-control.envoy.snapshot.routes.status.enabled" to true,
                "envoy-control.envoy.snapshot.routes.status.path-prefix" to "/status/",
                "envoy-control.envoy.snapshot.incoming-permissions.enabled" to true,
                "envoy-control.envoy.snapshot.incoming-permissions.overlapping-paths-fix" to true
            )
        )

        @JvmField
        @RegisterExtension
        val echo = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val echo2 = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val envoy1 = EnvoyExtension(envoyControl, localService = echo, config = echoConfig)

        @JvmField
        @RegisterExtension
        val envoy2 = EnvoyExtension(envoyControl, localService = echo2, config = echo2Config)
    }

    @BeforeEach
    fun startRecordingRBACLogs() {
        envoy1.recordRBACLogs()

        consul.server.operations.registerService(
            id = "echo",
            name = "echo",
            address = envoy1.container.ipAddress(),
            port = EnvoyContainer.INGRESS_LISTENER_CONTAINER_PORT,
            tags = listOf("mtls:enabled")
        )

        untilAsserted {
            assertThat(envoy2.container.admin().isEndpointHealthy("echo", envoy1.container.ipAddress())).isTrue()
        }
    }

    @Test
    fun `echo should be able to access endpoint when unlistedEndpointsPolicy is log`() {
        // when
        val echoResponse = envoy2.egressOperations.callService("echo", pathAndQuery = "/pa-al-in/ad/pu/XXXXXXXXXXXXXXX")

        // then
        assertThat(echoResponse).isOk()
        assertThat(envoy1.container).hasOneAccessAllowedWithActionLog(
            protocol = "https",
            path = "/pa-al-in/ad/pu/XXXXXXXXXXXXXXX",
            method = "GET",
            clientName = "echo2",
            trustedClient = true
        )
    }
}
