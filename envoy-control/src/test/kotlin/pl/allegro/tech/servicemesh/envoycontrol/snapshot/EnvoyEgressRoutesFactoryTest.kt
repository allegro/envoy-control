package pl.allegro.tech.servicemesh.envoycontrol.snapshot

import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.groups.hasHeaderToAdd
import pl.allegro.tech.servicemesh.envoycontrol.groups.hasNoHeaderToAdd

internal class EnvoyEgressRoutesFactoryTest {

    val clusters = mapOf("srv1" to "srv1")

    @Test
    fun `should add client identity header if incoming permissions are enabled`() {
        // given
        val routesFactory = EnvoyEgressRoutesFactory(SnapshotProperties().apply {
            incomingPermissions.enabled = true
        })

        // when
        val routeConfig = routesFactory.createEgressRouteConfig("client1", clusters)

        // then
        routeConfig
            .hasHeaderToAdd("x-service-name", "client1")
    }

    @Test
    fun `should not add client identity header if incoming permissions are disabled`() {
        // given
        val routesFactory = EnvoyEgressRoutesFactory(SnapshotProperties().apply {
            incomingPermissions.enabled = false
        })

        // when
        val routeConfig = routesFactory.createEgressRouteConfig("client1", clusters)

        // then
        routeConfig
            .hasNoHeaderToAdd("x-service-name")
    }
}
