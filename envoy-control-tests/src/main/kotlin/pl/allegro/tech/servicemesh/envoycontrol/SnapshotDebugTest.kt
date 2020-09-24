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
        waitForReadyServices("echo")

        untilAsserted {
            // when
            val snapshot = envoyControl1.getSnapshot(nodeMetadata)
            val edsVersion = envoyContainer1.admin().statValue("cluster.echo.version")
            val cdsVersion = envoyContainer1.admin().statValue("cluster_manager.cds.version")
            val rdsVersion = envoyContainer1.admin().statValue("http.egress_http.rds.default_routes.version")
            val ldsVersion = envoyContainer1.admin().statValue("listener_manager.lds.version")

            // then
            assertThat(snapshot.versions!!.clusters.metric).isEqualTo(cdsVersion)
            assertThat(snapshot.versions.endpoints.metric).isEqualTo(edsVersion)
            assertThat(snapshot.versions.routes.metric).isEqualTo(rdsVersion)
            assertThat(snapshot.versions.listeners.metric).isEqualTo(ldsVersion)
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
            assertThat(snapshot.snapshot!!["clusters"]).isNotEmpty()
            assertThat(snapshot.snapshot["routes"]).isNotEmpty()
            assertThat(snapshot.snapshot["endpoints"]).isNotEmpty()
            assertThat(snapshot.snapshot["listeners"]).isNotEmpty()
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
     }
    }
""".trim()

    @Test
    open fun `should inform about missing snapshot when given node does not exist`() {
        // when
        val snapshot = envoyControl1.getSnapshot(missingNodeJson)

        // then
        assertThat(snapshot.found).isFalse()
    }

    @Test
    open fun `should return global snapshot debug info from xds`() {
        untilAsserted {
            // when
            val snapshot = envoyControl1.getGlobalSnapshot(xds = true)

            // then
            assertThat(snapshot.snapshot!!["clusters"]).isNotEmpty()
            assertThat(snapshot.snapshot["endpoints"]).isNotEmpty()
            assertThat(snapshot.snapshot["clusters"].first()["edsClusterConfig"]["edsConfig"].toString()).contains("envoy-control-xds")
        }
    }

    @Test
    open fun `should return global snapshot debug info from ads`() {
        untilAsserted {
            // when
            val snapshotXdsNull = envoyControl1.getGlobalSnapshot(xds = null)
            val snapshotXdsFalse = envoyControl1.getGlobalSnapshot(xds = false)

            // then
            assertThat(snapshotXdsNull.snapshot!!["clusters"]).isNotEmpty()
            assertThat(snapshotXdsNull.snapshot["endpoints"]).isNotEmpty()
            assertThat(snapshotXdsFalse.snapshot!!["clusters"]).isNotEmpty()
            assertThat(snapshotXdsFalse.snapshot["endpoints"]).isNotEmpty()
            assertThat(snapshotXdsNull.snapshot["clusters"].first()["edsClusterConfig"]["edsConfig"].toString()).contains("ads")
            assertThat(snapshotXdsFalse.snapshot["clusters"].first()["edsClusterConfig"]["edsConfig"].toString()).contains("ads")
        }
    }
}
