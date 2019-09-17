package pl.allegro.tech.servicemesh.envoycontrol.reliability

import org.testcontainers.containers.wait.strategy.Wait
import pl.allegro.tech.servicemesh.envoycontrol.testcontainers.GenericContainer

class ToxiproxyContainer :
    GenericContainer<ToxiproxyContainer>("shopify/toxiproxy:latest") {

    companion object {
        const val internalToxiproxyPort = 8474
    }

    override fun configure() {
        super.configure()
        waitingFor(Wait.forHttp("/version").forPort(internalToxiproxyPort))
    }
}
