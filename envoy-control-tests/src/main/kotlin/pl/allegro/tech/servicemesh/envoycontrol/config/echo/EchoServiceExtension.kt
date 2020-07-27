package pl.allegro.tech.servicemesh.envoycontrol.config.echo

class EchoServiceExtension : ServiceExtension(EchoContainer()) {

    override val container: EchoContainer = super.container as EchoContainer
}
