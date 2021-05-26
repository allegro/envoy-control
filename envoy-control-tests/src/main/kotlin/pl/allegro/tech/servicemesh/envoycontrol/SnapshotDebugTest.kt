package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.AdsAllDependencies
import pl.allegro.tech.servicemesh.envoycontrol.config.DeltaAdsAllDependencies
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension

open class WildcardSnapshotDebugTest : SnapshotDebugTest {
    companion object {
        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(consul, properties = mapOf(
            "envoy-control.envoy.snapshot.outgoing-permissions.services-allowed-to-use-wildcard" to setOf("echo2", "test-service")
        ))

        @JvmField
        @RegisterExtension
        val service = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val envoy = EnvoyExtension(envoyControl, service, config = AdsAllDependencies)
    }

    override fun consul() = consul

    override fun envoyControl() = envoyControl

    override fun envoy() = envoy

    override fun service() = service
}

open class DeltaWildcardSnapshotDebugTest : SnapshotDebugTest {
    companion object {
        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(consul, properties = mapOf(
            "envoy-control.envoy.snapshot.outgoing-permissions.services-allowed-to-use-wildcard" to setOf("echo2", "test-service")
        ))

        @JvmField
        @RegisterExtension
        val service = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val envoy = EnvoyExtension(envoyControl, service, config = DeltaAdsAllDependencies)
    }

    override fun consul() = consul

    override fun envoyControl() = envoyControl

    override fun envoy() = envoy

    override fun service() = service
}

open class RandomSnapshotDebugTest : SnapshotDebugTest {
    companion object {
        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(consul)

        @JvmField
        @RegisterExtension
        val service = EchoServiceExtension()

        @JvmField
        @RegisterExtension
        val envoy = EnvoyExtension(envoyControl, service)
    }

    override fun consul() = consul

    override fun envoyControl() = envoyControl

    override fun envoy() = envoy

    override fun service() = service
}

interface SnapshotDebugTest {

    @Test
    fun `should return snapshot debug info containing snapshot versions`() {
        // given
        consul().server.operations.registerService(service(), name = "echo")
        val nodeMetadata = envoy().container.admin().nodeInfo()

        untilAsserted {
            // when
            val snapshot = envoyControl().app.getSnapshot(nodeMetadata)
            val edsVersion = envoy().container.admin().statValue("cluster.echo.version")
            val cdsVersion = envoy().container.admin().statValue("cluster_manager.cds.version")
            val rdsVersion = envoy().container.admin().statValue("http.egress_http.rds.default_routes.version")
            val ldsVersion = envoy().container.admin().statValue("listener_manager.lds.version")

            // then
            assertThat(snapshot.versions!!.clusters.metric).isEqualTo(cdsVersion)
            assertThat(snapshot.versions.endpoints.metric).isEqualTo(edsVersion)
            assertThat(snapshot.versions.routes.metric).isEqualTo(rdsVersion)
            assertThat(snapshot.versions.listeners.metric).isEqualTo(ldsVersion)
        }
    }

    @Test
    fun `should return snapshot debug info containing snapshot contents`() {
        // given
        consul().server.operations.registerService(service(), name = "echo")
        val nodeMetadata = envoy().container.admin().nodeInfo()

        untilAsserted {
            // when
            val snapshot = envoyControl().app.getSnapshot(nodeMetadata)

            // then
            assertThat(snapshot.snapshot!!["clusters"]).isNotEmpty()
            assertThat(snapshot.snapshot["routes"]).isNotEmpty()
            assertThat(snapshot.snapshot["endpoints"]).isNotEmpty()
            assertThat(snapshot.snapshot["listeners"]).isNotEmpty()
        }
    }

    fun missingNodeJson(): String {
        return """{
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
    }

    @Test
    fun `should inform about missing snapshot when given node does not exist`() {
        // when
        val snapshot = envoyControl().app.getSnapshot(missingNodeJson())

        // then
        assertThat(snapshot.found).isFalse()
    }

    @Test
    fun `should return global snapshot debug info from xds`() {
        untilAsserted {
            // when
            val snapshot = envoyControl().app.getGlobalSnapshot(xds = true)

            // then
            assertThat(snapshot.snapshot!!["clusters"]).isNotEmpty()
            assertThat(snapshot.snapshot["endpoints"]).isNotEmpty()
            assertThat(snapshot.snapshot["clusters"].first()["edsClusterConfig"]["edsConfig"].toString()).contains("envoy-control-xds")
        }
    }

    @Test
    fun `should return global snapshot debug info from ads`() {
        untilAsserted {
            // when
            val snapshotXdsNull = envoyControl().app.getGlobalSnapshot(xds = null)
            val snapshotXdsFalse = envoyControl().app.getGlobalSnapshot(xds = false)

            // then
            assertThat(snapshotXdsNull.snapshot!!["clusters"]).isNotEmpty()
            assertThat(snapshotXdsNull.snapshot["endpoints"]).isNotEmpty()
            assertThat(snapshotXdsFalse.snapshot!!["clusters"]).isNotEmpty()
            assertThat(snapshotXdsFalse.snapshot["endpoints"]).isNotEmpty()
            assertThat(snapshotXdsNull.snapshot["clusters"].first()["edsClusterConfig"]["edsConfig"].toString()).contains(
                "ads"
            )
            assertThat(snapshotXdsFalse.snapshot["clusters"].first()["edsClusterConfig"]["edsConfig"].toString()).contains(
                "ads"
            )
        }
    }

    fun consul(): ConsulExtension
    fun envoyControl(): EnvoyControlExtension
    fun envoy(): EnvoyExtension
    fun service(): EchoServiceExtension
}
