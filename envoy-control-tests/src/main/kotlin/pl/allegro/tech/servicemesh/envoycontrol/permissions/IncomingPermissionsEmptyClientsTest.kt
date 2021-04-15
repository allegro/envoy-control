package pl.allegro.tech.servicemesh.envoycontrol.permissions

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.hasOneAccessDenialWithActionBlock
import pl.allegro.tech.servicemesh.envoycontrol.assertions.hasOneAccessDenialWithActionLog
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isForbidden
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isFrom
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isOk
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo1EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo2EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.OAuthServerExtension

internal class IncomingPermissionsEmptyClientsTest {
    companion object {

        // language=yaml
        private val echoConfig = Echo1EnvoyAuthConfig.copy(
            configOverride = """
            node:
              metadata:
                proxy_settings:
                  incoming:
                    unlistedEndpointsPolicy: log
                    endpoints: 
                    - path: /blocked-for-all
                      clients: []
                  outgoing:
                    dependencies:
                      - service: "echo"
                      - service: "echo2"
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
                      - service: "echo2"
        """.trimIndent()
        )

        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(
            consul, mapOf(
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

        @JvmField
        @RegisterExtension
        val oauth = OAuthServerExtension()
    }
    /*
    curl --request POST \
    --url 'http://localhost:8080/default/token' \
    --header 'content-type: application/x-www-form-urlencoded' \
    --data grant_type=client_credentials \
    --data client_id=debugger \
    --data client_secret=someSecret
    eyJraWQiOiJtb2NrLW9hdXRoMi1zZXJ2ZXIta2V5IiwidHlwIjoiSldUIiwiYWxnIjoiUlMyNTYifQ.eyJzdWIiOiJkZWJ1Z2dlciIsImF1ZCI6InNvbWVzY29wZSIsImFjciI6ImFjciIsIm5iZiI6MTYxODM4NTI3MiwiYXpwIjoiZGVidWdnZXIiLCJpc3MiOiJodHRwOlwvXC9sb2NhbGhvc3Q6ODA4MFwvZGVmYXVsdCIsImV4cCI6MTYxODM4ODg3MiwiaWF0IjoxNjE4Mzg1MjcyLCJub25jZSI6IjU2NzgiLCJqdGkiOiJlNTY4OTRiZC01NDJiLTQ2NmQtOGY2ZC0xMTgwM2I0N2ZkNmUiLCJ0aWQiOiJkZWZhdWx0In0.NXYwdrcgHVU1MB4s6Kr66IN0U7C0SawoxigVnWy6xCRz6sHZ-ACocpNEjeL7GKzNxfgaDkC5bh20dIs9hu-wWdh5sIPWhRwhDU9vkDAsaGfleKo3bDSyjnrpWFQHSxorE1YKAThaDihu_vye0WzVSHid4eJruGJa4uN2kXCBLpPu6csw-dY66ik2Di350Oi5HzBKhHSm0tFJ37Xc8iUXhk4HR4iOAjvBjP-ZuMNgO29sxmkhMorjOnax_jrcSg9g0OEfJkSYeobOMuV2jEb7bY_Da77J6UtQ0LMDrEfVp2zsILKUJl5zlUi9EJNIpVjP4oL2ZzKK83HyQm6YLzbTKw
    */
    @Test
    fun `echo should deny clients access to 'blocked-for-all' endpoint`() {
        // when
        val echoResponse = envoy1.ingressOperations.callLocalService("/blocked-for-all")

        // then
        assertThat(echoResponse).isForbidden()
        assertThat(envoy1.container).hasOneAccessDenialWithActionBlock(
            protocol = "http",
            path = "/blocked-for-all",
            method = "GET",
            clientName = "",
            trustedClient = false,
            clientIp = envoy1.container.gatewayIp()
        )
    }

    @Test
    fun `echo should allow clients access to 'unlisted' endpoint and log it`() {
        // when
        val echoResponse = envoy1.ingressOperations.callLocalService("/unlisted")

        // then
        assertThat(echoResponse).isOk().isFrom(echo)
        assertThat(envoy1.container).hasOneAccessDenialWithActionLog(
            protocol = "http",
            path = "/unlisted",
            method = "GET",
            clientName = "",
            clientIp = envoy1.container.gatewayIp()
        )
    }

    @Test
    fun `echo2 should allow clients access to 'logged-for-all' endpoint and log it`() {
        // when
        val echo2Response = envoy2.ingressOperations.callLocalService("/logged-for-all")

        // then
        assertThat(echo2Response).isOk().isFrom(echo2)
        assertThat(envoy2.container).hasOneAccessDenialWithActionLog(
            protocol = "http",
            path = "/logged-for-all",
            method = "GET",
            clientName = "",
            clientIp = envoy2.container.gatewayIp()
        )
    }

    @Test
    fun `echo2 should deny clients access to 'unlisted' endpoint`() {
        // when
        val echo2Response = envoy2.ingressOperations.callLocalService("/unlisted")

        // then
        assertThat(echo2Response).isForbidden()
        assertThat(envoy2.container).hasOneAccessDenialWithActionBlock(
            protocol = "http",
            path = "/unlisted",
            method = "GET",
            clientName = "",
            trustedClient = false,
            clientIp = envoy2.container.gatewayIp()
        )
    }

    @BeforeEach
    fun startRecordingRBACLogs() {
        listOf(envoy1, envoy2).forEach { it.recordRBACLogs() }
    }

    @AfterEach
    fun cleanupTest() {
        listOf(envoy1, envoy2).forEach {
            it.container.admin().resetCounters()
            it.container.logRecorder.stopRecording()
        }
    }
}
