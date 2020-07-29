package pl.allegro.tech.servicemesh.envoycontrol.config.service

import org.junit.jupiter.api.extension.ExtensionContext
import java.util.LinkedList
import java.util.Queue

class EchoServiceExtension : ServiceExtension<EchoContainer> {

    companion object {
        private val freeContainers: Queue<EchoContainer> = LinkedList()
        private val usedContainers = mutableMapOf<EchoServiceExtension, EchoContainer>()
    }

    var started = false
    private var container: EchoContainer? = null

    override fun container() = container!!

    override fun beforeAll(context: ExtensionContext) {
        if (started) {
            return
        }

        container = freeContainers.poll()?: EchoContainer()
        usedContainers[this] = container!!

        container!!.start()
        started = true
    }

    override fun afterAll(context: ExtensionContext) {
        val container = usedContainers.remove(this)!!
        freeContainers.offer(container)
    }
}
