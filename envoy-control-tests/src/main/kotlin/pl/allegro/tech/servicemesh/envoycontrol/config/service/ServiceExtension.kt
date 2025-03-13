package pl.allegro.tech.servicemesh.envoycontrol.config.service

import pl.allegro.tech.servicemesh.envoycontrol.config.sharing.BeforeAndAfterAllOnce

interface ServiceExtension<T : ServiceContainer> : BeforeAndAfterAllOnce {

    fun container(): T
}
