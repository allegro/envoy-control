package pl.allegro.tech.servicemesh.envoycontrol.config.containers

import pl.allegro.tech.servicemesh.envoycontrol.config.BaseEnvoyTest

class HttpsEchoContainer : SSLGenericContainer<HttpsEchoContainer>("mendhak/http-https-echo") {

    override fun configure() {
        super.configure()
        withNetwork(BaseEnvoyTest.network)
    }
}
