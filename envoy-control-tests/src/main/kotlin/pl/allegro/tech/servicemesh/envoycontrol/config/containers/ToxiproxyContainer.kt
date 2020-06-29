package pl.allegro.tech.servicemesh.envoycontrol.config.containers

import eu.rekawek.toxiproxy.ToxiproxyClient
import org.testcontainers.containers.wait.strategy.Wait
import pl.allegro.tech.servicemesh.envoycontrol.testcontainers.GenericContainer
import java.util.LinkedList

class ToxiproxyContainer(exposedPortsCount: Int = 0) :
    GenericContainer<ToxiproxyContainer>("shopify/toxiproxy:2.1.4") {

    companion object {
        const val internalToxiproxyPort = 8474
    }

    val client by lazy { ToxiproxyClient("localhost", getMappedPort(internalToxiproxyPort)) }

    private val freeExposedPorts = (1..exposedPortsCount).map { portOffset ->
        val port = internalToxiproxyPort + portOffset
        this.addExposedPort(port)
        port
    }.let { LinkedList(it) }

    override fun configure() {
        super.configure()
        waitingFor(Wait.forHttp("/version").forPort(internalToxiproxyPort))
    }

    fun createProxy(targetIp: String, targetPort: Int): String {
        val listenPort = freeExposedPorts.pop()
        client.createProxy(
            "$targetIp:$targetPort",
            "$allInterfaces:$listenPort",
            "$targetIp:$targetPort"
        ).enable()
        return "http://$containerIpAddress:${getMappedPort(listenPort)}"
    }
}
