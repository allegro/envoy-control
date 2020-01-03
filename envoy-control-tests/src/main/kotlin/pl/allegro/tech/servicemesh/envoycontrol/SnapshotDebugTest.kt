package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration

open class SnapshotDebugTest : EnvoyControlTestConfiguration() {

    companion object {
        @JvmStatic
        @BeforeAll
        fun setupTest() {
            setup()
        }
    }

    @Test
    open fun `should return snapshot debug info containing snapshot versions`() {
        // given
        registerService(name = "echo")
        val nodeMetadata = envoyContainer1.admin().nodeInfo()

        untilAsserted {
            // when
            val snapshot = envoyControl1.getSnapshot(nodeMetadata)

            // then
            assertThat(snapshot).isNotEmpty()
            assertThat(snapshot)
                .contains("clusters: ")
                .contains("endpoints: ")
                .contains("routes: ")
                .contains("listeners: ")
        }
    }

    @Test
    open fun `should return snapshot debug info containing snapshot contents`() {
        // given
        registerService(name = "echo")
        val nodeMetadata = envoyContainer1.admin().nodeInfo()

        untilAsserted {
            // when
            val snapshot = envoyControl1.getSnapshot(nodeMetadata)

            // then
            assertThat(snapshot).isNotEmpty()
            assertThat(snapshot)
                .contains("clusters=SnapshotResources")
                .contains("endpoints=SnapshotResources")
                .contains("routes=SnapshotResources")
                .contains("listeners=SnapshotResources")
        }
    }

    private val missingNodeJson = """{
     "metadata": {
      "service_name": "service-mesh-service-first",
      "identity": "",
      "service_version": "0.1.16-SKYHELIX-839-eds-version-metric-SNAPSHOT",
      "proxy_settings": {
       "incoming": {
        "endpoints": null,
        "healthCheck": null,
        "roles": null,
        "timeoutPolicy": null
       },
       "outgoing": {
        "dependencies": [
         {
          "handleInternalRedirect": null,
          "timeoutPolicy": null,
          "endpoints": [],
          "domain": null,
          "service": "*"
         }
        ]
       }
      },
      "ads": true
     },
     "locality": {
      "zone": "dev-dc4"
     },
     "build_version": "b7bef67c256090919a4585a1a06c42f15d640a09/1.13.0-dev/Clean/RELEASE/BoringSSL"
    }
""".trim()

    @Test
    open fun `should inform about missing snapshot when given node does not exist`() {
        // when
        val snapshot = envoyControl1.getSnapshot(missingNodeJson)

        // then
        assertThat(snapshot).isNotEmpty()
        assertThat(snapshot).contains("snapshot missing")
    }
}
