package pl.allegro.tech.servicemesh.envoycontrol.config.echo

interface ServiceContainer {

    fun ipAddress(): String

    fun port(): Int

    fun start()

    fun stop()
}
