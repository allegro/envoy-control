package pl.allegro.tech.servicemesh.envoycontrol.config.service

import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.ResponseWithBody

interface ServiceContainer {

    fun ipAddress(): String

    fun port(): Int

    fun start()

    fun stop()
}
