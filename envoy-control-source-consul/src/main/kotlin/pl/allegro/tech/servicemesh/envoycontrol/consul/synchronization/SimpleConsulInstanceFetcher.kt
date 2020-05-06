package pl.allegro.tech.servicemesh.envoycontrol.consul.synchronization

import com.ecwid.consul.v1.ConsulClient
import com.ecwid.consul.v1.QueryParams
import com.ecwid.consul.v1.health.model.HealthService
import pl.allegro.tech.servicemesh.envoycontrol.synchronization.ControlPlaneInstanceFetcher
import java.net.URI

class SimpleConsulInstanceFetcher(
    private val consulClient: ConsulClient,
    private val envoyControlAppName: String
) : ControlPlaneInstanceFetcher {

    override fun instances(zone: String): List<URI> = toServiceUri(findInstances(zone))

    private fun toServiceUri(instances: MutableList<HealthService>) =
        instances.map { instance -> createURI(instance.service.address, instance.service.port) }

    private fun findInstances(nonLocalDc: String) =
        consulClient.getHealthServices(envoyControlAppName, true, QueryParams(nonLocalDc)).value

    private fun createURI(host: String, port: Int) = URI.create("http://$host:$port/")
}
