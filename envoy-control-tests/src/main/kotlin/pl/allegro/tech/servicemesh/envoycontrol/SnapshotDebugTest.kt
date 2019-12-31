package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions
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
    open fun `getting snapshot debug info contains all information`() {
        // given
        registerService(name = "echo")
        val nodeMetadata = envoyContainer1.admin().nodeInfo()

        untilAsserted {
            // when
            val snapshot = envoyControl1.getSnapshot(nodeMetadata)

            // then
            Assertions.assertThat(snapshot).isNotEmpty()
            Assertions.assertThat(snapshot)
                .contains("clusters: ")
                .contains("endpoints: ")
                .contains("routes: ")
                .contains("listeners: ")
            Assertions.assertThat(snapshot)
                .contains("clusters=SnapshotResources")
                .contains("endpoints=SnapshotResources")
                .contains("routes=SnapshotResources")
                .contains("listeners=SnapshotResources")
        }
    }

    val missingNodeJson = """{
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
    open fun `getting missing snapshot results returns valid description`() {
        // when
        val snapshot = envoyControl1.getSnapshot(missingNodeJson)

        // then
        Assertions.assertThat(snapshot).isNotEmpty()
        Assertions.assertThat(snapshot).contains("snapshot missing")
    }
}
