package pl.allegro.tech.servicemesh.envoycontrol.config.service

import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.ResponseWithBody
import pl.allegro.tech.servicemesh.envoycontrol.config.sharing.BeforeAndAfterAllOnce
import pl.allegro.tech.servicemesh.envoycontrol.config.sharing.ContainerExtension

class HttpsEchoExtension : ContainerExtension(), ServiceExtension<HttpsEchoContainer>, UpstreamService {
    override fun id(): String = container.id()
    override fun isSourceOf(response: ResponseWithBody): Boolean = container.isSourceOf(response)
    override val container: HttpsEchoContainer = HttpsEchoContainer()
    override fun container(): HttpsEchoContainer = container
    override val ctx: BeforeAndAfterAllOnce.Context = BeforeAndAfterAllOnce.Context()
}
